package br.com.viriato.agente;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** O laço do agente contra um "Viriato" simulado (HTTP) e uma "SEFAZ" simulada (HTTPS com certificado do cliente). */
class ServicoTest {
    @TempDir Path dir;
    HttpServer viriato;
    final List<Map<String, Object>> contatos = new CopyOnWriteArrayList<>();
    final List<String> tokens = new CopyOnWriteArrayList<>();
    final Map<Long, Map<String, Object>> resultados = new java.util.concurrent.ConcurrentHashMap<>();
    final Deque<String> respostas = new ArrayDeque<>();   // corpo do próximo /contato; vazio = sem tarefas
    volatile int statusDoViriato = 200;

    @BeforeEach
    void subir() throws Exception {
        viriato = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        viriato.createContext("/api/agente/contato", ex -> {
            tokens.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
            contatos.add(Json.objeto(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            responder(ex, statusDoViriato, statusDoViriato == 200 ? (respostas.isEmpty() ? "{\"tarefas\":[]}" : respostas.poll()) : "{\"erro\":\"não\"}");
        });
        viriato.createContext("/api/agente/tarefas/", ex -> {
            String[] partes = ex.getRequestURI().getPath().split("/");
            resultados.put(Long.parseLong(partes[4]), Json.objeto(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            ex.sendResponseHeaders(204, -1);
            ex.close();
        });
        viriato.createContext("/api/auth/agente/parear", ex -> {
            Map<String, Object> c = Json.objeto(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if ("ABCD-2345".equals(c.get("codigo"))) responder(ex, 200, "{\"token\":\"vagt_novo\"}");
            else responder(ex, 422, "{\"erro\":\"Código inválido ou expirado\"}");
        });
        viriato.start();
    }

    @AfterEach void descer() { viriato.stop(0); }

    private static void responder(com.sun.net.httpserver.HttpExchange ex, int status, String corpo) throws java.io.IOException {
        byte[] b = corpo.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream o = ex.getResponseBody()) { o.write(b); }
    }

    private String url() { return "http://127.0.0.1:" + viriato.getAddress().getPort(); }

    private static String tarefa(long id, String url, String thumbprint, String corpo) {
        return Json.escrever(Map.of("tarefas", List.of(Map.of("id", id, "tipo", "HTTPS_MTLS", "url", url, "contentType", "application/soap+xml",
                "corpo", Base64.getEncoder().encodeToString(corpo.getBytes(StandardCharsets.UTF_8)), "thumbprint", thumbprint))));
    }

    @Test
    void informaOsCertificadosUmaVezExecutaATarefaComOCertificadoPedidoEDevolveOResultado() throws Exception {
        var repo = new RepositorioPkcs12(Certs.repositorioDoComputador(dir, "ALFA", "BETA"), Certs.SENHA);
        try (var sefaz = new Certs.ServidorMtls(dir, repo.repositorio())) {
            var beta = repo.listar().stream().filter(l -> l.alias().equals("beta")).findFirst().orElseThrow();
            var exec = new ExecutorHttps(repo, List.of("127.0.0.1"), false, sefaz.confianca(), Duration.ofSeconds(20));
            var servico = new Servico(repo, new ClienteViriato(url(), "vagt_abc", null), exec, "9.9");

            assertThat(servico.umCiclo()).isZero();
            Map<String, Object> primeiro = contatos.get(0);
            assertThat(primeiro.get("versao")).isEqualTo("9.9");
            List<?> ders = (List<?>) primeiro.get("certificados");
            assertThat(ders).hasSize(2);                                                                   // a lista completa na primeira vez
            X509Certificate lido = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(Base64.getDecoder().decode((String) ders.get(0))));
            assertThat(lido.getSubjectX500Principal().getName()).startsWith("CN=");
            assertThat(String.valueOf(primeiro)).doesNotContain("PRIVATE").doesNotContain(new String(Certs.SENHA));   // só a parte pública sai
            assertThat(tokens.get(0)).isEqualTo("Bearer vagt_abc");

            respostas.add(tarefa(7, sefaz.url(), beta.thumbprint(), "<soap/>"));
            assertThat(servico.umCiclo()).isEqualTo(1);
            assertThat(contatos.get(1)).doesNotContainKey("certificados");                                 // sem mudança, só o batimento
            Map<String, Object> r = resultados.get(7L);
            assertThat(r.get("httpStatus")).isEqualTo(200L);
            assertThat(new String(Base64.getDecoder().decode((String) r.get("corpo")), StandardCharsets.UTF_8)).isEqualTo("OK:CN=BETA,O=Teste,C=BR");   // a SEFAZ viu o certificado da BETA
            assertThat(sefaz.corpos).containsExactly("<soap/>");
        }
    }

    @Test
    void tarefaInvalidaOuForaDaPermissaoViraErroSemDerrubarOAgente() throws Exception {
        var repo = new RepositorioPkcs12(Certs.repositorioDoComputador(dir, "ALFA"), Certs.SENHA);
        var exec = new ExecutorHttps(repo, Configuracao.HOSTS_PADRAO, true, null, Duration.ofSeconds(5));
        var servico = new Servico(repo, new ClienteViriato(url(), "vagt_abc", null), exec, "1");
        String t = repo.listar().get(0).thumbprint();
        respostas.add(tarefa(1, "https://evil.example.com/roubar", t, "x"));
        respostas.add(Json.escrever(Map.of("tarefas", List.of(Map.of("id", 2, "tipo", "RODAR_COMANDO", "url", "x", "contentType", "x", "corpo", "", "thumbprint", t)))));
        servico.umCiclo();
        servico.umCiclo();
        assertThat((String) resultados.get(1L).get("erro")).contains("fora dos domínios permitidos");
        assertThat((String) resultados.get(2L).get("erro")).contains("Tipo de tarefa desconhecido");
        assertThat(resultados.get(1L)).doesNotContainKey("corpo");
        assertThat(servico.umCiclo()).isZero();   // segue funcionando
    }

    @Test
    void agenteNaoReconhecidoParaDeInsistirEQuedaDoServidorSoEspera() throws Exception {
        var repo = new RepositorioPkcs12(Certs.repositorioDoComputador(dir, "ALFA"), Certs.SENHA);
        var servico = new Servico(repo, new ClienteViriato(url(), "vagt_revogado", null), new ExecutorHttps(repo, List.of(), true, null, Duration.ofSeconds(5)), "1");
        statusDoViriato = 403;
        assertThatThrownBy(servico::umCiclo).isInstanceOf(ClienteViriato.NaoAutorizado.class);
        servico.executar(Duration.ofMillis(10));   // sai sozinho, sem ficar em laço
        assertThat(contatos).hasSizeGreaterThanOrEqualTo(2);

        statusDoViriato = 500;
        assertThatThrownBy(servico::umCiclo).isInstanceOf(java.io.IOException.class).isNotInstanceOf(ClienteViriato.NaoAutorizado.class);
    }

    @Test
    void pareamentoTrocaOCodigoPeloTokenEGuardaConfiguracaoSoParaOUsuario() throws Exception {
        String token = new ClienteViriato(url(), null, null).parear("ABCD-2345", "PC da Maria", "1.0");
        assertThat(token).isEqualTo("vagt_novo");
        assertThatThrownBy(() -> new ClienteViriato(url(), null, null).parear("ZZZZ-9999", "PC", "1.0")).hasMessageContaining("Código inválido ou expirado");

        new Configuracao(url(), token, "PC da Maria", Configuracao.HOSTS_PADRAO, null, null).salvar(dir);
        Configuracao lida = Configuracao.carregar(dir);
        assertThat(lida.servidor()).isEqualTo(url());
        assertThat(lida.token()).isEqualTo("vagt_novo");
        assertThat(lida.hostsPermitidos()).containsExactly("fazenda.gov.br", "gov.br");
        if (!Confianca.windows()) {
            assertThat(java.nio.file.Files.getPosixFilePermissions(Configuracao.arquivo(dir))).containsExactlyInAnyOrder(java.nio.file.attribute.PosixFilePermission.OWNER_READ, java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
        }
        assertThatThrownBy(() -> Configuracao.carregar(dir.resolve("outra"))).hasMessageContaining("ainda não foi ligado");
    }
}
