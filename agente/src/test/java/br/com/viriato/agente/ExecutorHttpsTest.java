package br.com.viriato.agente;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** TLS mútuo de verdade (servidor local que exige certificado) com o certificado escolhido entre vários do "computador". */
class ExecutorHttpsTest {
    @TempDir Path dir;

    private RepositorioPkcs12 computador(String... cns) throws Exception { return new RepositorioPkcs12(Certs.repositorioDoComputador(dir, cns), Certs.SENHA); }

    @Test
    void apresentaOCertificadoEscolhidoPelaImpressaoDigitalENaoOutroDoRepositorio() throws Exception {
        var repo = computador("EMPRESA UM", "EMPRESA DOIS", "EMPRESA TRES");
        try (var srv = new Certs.ServidorMtls(dir, repo.repositorio())) {
            var exec = new ExecutorHttps(repo, List.of("127.0.0.1"), false, srv.confianca(), Duration.ofSeconds(20));
            for (var l : repo.listar()) {
                var r = exec.postar(srv.url(), "application/soap+xml", "<pedido/>".getBytes(StandardCharsets.UTF_8), l.thumbprint().toLowerCase());   // minúsculas também vale
                assertThat(r.httpStatus()).isEqualTo(200);
                assertThat(new String(r.corpo(), StandardCharsets.UTF_8)).isEqualTo("OK:" + l.certificado().getSubjectX500Principal().getName());
            }
            assertThat(srv.apresentados).hasSize(3).containsExactlyInAnyOrder("CN=EMPRESA UM,O=Teste,C=BR", "CN=EMPRESA DOIS,O=Teste,C=BR", "CN=EMPRESA TRES,O=Teste,C=BR");
            assertThat(srv.corpos).containsOnly("<pedido/>");
        }
    }

    @Test
    void recusaCertificadoQueNaoEstaNoComputadorSemChamarNinguem() throws Exception {
        var repo = computador("EMPRESA UM");
        try (var srv = new Certs.ServidorMtls(dir, repo.repositorio())) {
            var exec = new ExecutorHttps(repo, List.of("127.0.0.1"), false, srv.confianca(), Duration.ofSeconds(20));
            assertThatThrownBy(() -> exec.postar(srv.url(), "text/plain", new byte[0], "0".repeat(40))).hasMessageContaining("não está instalado neste computador");
            assertThat(srv.apresentados).isEmpty();
        }
    }

    @Test
    void soChamaHttpsDeDominiosPermitidos() throws Exception {
        var repo = computador("EMPRESA UM");
        String t = repo.listar().get(0).thumbprint();
        var exec = new ExecutorHttps(repo, Configuracao.HOSTS_PADRAO, true, null, Duration.ofSeconds(5));
        for (String ruim : new String[]{"http://www1.nfe.fazenda.gov.br/x", "https://evil.example.com/x", "https://fazenda.gov.br.evil.com/x", "https://notgov.br/x", "https://www1.nfe.fazenda.gov.br:8443/x",
                "https://user:senha@www1.nfe.fazenda.gov.br/x", "ftp://www1.nfe.fazenda.gov.br/x", "isto nao e url", "file:///etc/passwd", "https://127.0.0.1/x"}) {
            assertThatThrownBy(() -> exec.postar(ruim, "text/plain", new byte[0], t)).as(ruim).isInstanceOf(java.io.IOException.class);
        }
        exec.conferirDestino("https://www1.nfe.fazenda.gov.br/NFeDistribuicaoDFe/NFeDistribuicaoDFe.asmx");   // não lança
        exec.conferirDestino("https://hom1.nfe.fazenda.gov.br:443/x");
        exec.conferirDestino("https://www.gov.br/x");
    }

    @Test
    void servidorQueNaoConfiamosNaoRecebeNada() throws Exception {
        var repo = computador("EMPRESA UM");
        try (var srv = new Certs.ServidorMtls(dir, repo.repositorio())) {
            // confiança padrão do Java: não conhece o certificado do servidor de teste, então a negociação falha antes de qualquer dado
            var exec = new ExecutorHttps(repo, List.of("127.0.0.1"), false, Confianca.gerenciadores(null), Duration.ofSeconds(20));
            assertThatThrownBy(() -> exec.postar(srv.url(), "text/plain", "segredo".getBytes(), repo.listar().get(0).thumbprint())).isInstanceOf(Exception.class);
            assertThat(srv.corpos).isEmpty();
        }
    }
}
