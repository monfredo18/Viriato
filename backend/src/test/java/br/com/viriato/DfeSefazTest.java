package br.com.viriato;

import static br.com.viriato.Crm.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.viriato.ged.DfeControleRepository;
import br.com.viriato.ged.DfeService;
import br.com.viriato.ged.SefazDfeConnector;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** Consulta à SEFAZ (Distribuição DF-e) com o certificado do computador (via agente local), controle de NSU e espera de 1 hora (conector simulado). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({Crm.StorageFake.class, DfeSefazTest.Falso.class})
class DfeSefazTest {
    record Chamada(String cnpj, String uf, String nsu, boolean viaAgente) {}

    static final List<Chamada> CHAMADAS = new ArrayList<>();
    static final Deque<SefazDfeConnector.Resposta> ROTEIRO = new ArrayDeque<>();

    @TestConfiguration
    static class Falso {
        @Bean @Primary
        SefazDfeConnector sefazFalso() {
            return new SefazDfeConnector() {
                public boolean habilitado() { return true; }
                public Resposta consultar(Postador postador, String cnpj, String uf, String nsu) throws java.io.IOException {
                    byte[] r = postador.postar("https://sefaz.teste/NFeDistribuicaoDFe", "application/soap+xml", "pedido".getBytes());   // a chamada com o certificado é do agente
                    CHAMADAS.add(new Chamada(cnpj, uf, nsu, new String(r).equals("RESPOSTA-DO-AGENTE")));
                    return ROTEIRO.poll();
                }
            };
        }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired DfeService dfe;
    @Autowired DfeControleRepository controles;

    static final String CNPJ = "11222333000181";

    /** Empresa atendida com um certificado lido por um agente do gestor (o CNPJ do titular a liga à empresa). */
    private record Cenario(long cli, String agente) {}

    private Cenario empresaComCertificado(Empresa e, String numero) throws Exception {
        long cli = cliente(mvc, json, e.gestor(), numero, "Dfe " + e.s(), "{\"cnpj\":\"" + CNPJ + "\",\"uf\":\"SP\"}");
        String agente = Agentes.parear(mvc, json, e.gestor(), "PC do gestor");
        Agentes.contato(mvc, agente, List.of(Agentes.der("EMPRESA TESTE:" + CNPJ, 200))).andExpect(status().isOk());
        return new Cenario(cli, agente);
    }

    private Agentes.Simulado agenteAtendendo(String token) { return new Agentes.Simulado(mvc, json, token, 200, "RESPOSTA-DO-AGENTE".getBytes()); }

    private static SefazDfeConnector.DocumentoDfe doc(String nsu, String esquema, byte[] xml) { return new SefazDfeConnector.DocumentoDfe(nsu, esquema, xml); }

    @Test
    void baixaNotasImportaIgnoraResumosGuardaNsuEEsperaUmaHora() throws Exception {
        Empresa e = empresa(mvc, json);
        Cenario cen = empresaComCertificado(e, "90");
        long cli = cen.cli();
        CHAMADAS.clear(); ROTEIRO.clear();
        byte[] n1 = XmlsFiscais.nfe(XmlsFiscais.chave("3526", 51), "07526557000100", CNPJ, "10", "2026-10-10T10:00:00-03:00", "100.00", "55");
        byte[] n2 = XmlsFiscais.nfe(XmlsFiscais.chave("3526", 52), "07526557000100", CNPJ, "11", "2026-10-11T10:00:00-03:00", "200.00", "55");
        ROTEIRO.add(new SefazDfeConnector.Resposta("138", "Documento(s) localizado(s)", "000000000000002", "000000000000004",
                List.of(doc("000000000000001", "procNFe_v4.00.xsd", n1), doc("000000000000002", "resNFe_v1.01.xsd", "<resNFe/>".getBytes()))));
        ROTEIRO.add(new SefazDfeConnector.Resposta("138", "Documento(s) localizado(s)", "000000000000004", "000000000000004",
                List.of(doc("000000000000003", "procNFe_v4.00.xsd", n2), doc("000000000000004", "procNFe_v4.00.xsd", n1))));   // n1 repetida: duplicada

        JsonNode r;
        try (var agente = agenteAtendendo(cen.agente())) {
            r = corpo(json, enviar(mvc, e.gestor(), "/api/clientes/" + cli + "/sefaz/consultar", "{}").andExpect(status().isOk()));
            assertThat(agente.recebidas).hasSize(2);                                                              // cada rodada foi uma tarefa do agente
            assertThat(agente.recebidas.get(0).get("tipo").asText()).isEqualTo("HTTPS_MTLS");
            assertThat(agente.recebidas.get(0).get("url").asText()).isEqualTo("https://sefaz.teste/NFeDistribuicaoDFe");
            assertThat(agente.recebidas.get(0).get("thumbprint").asText()).matches("[0-9A-F]{40}");              // qual certificado do computador usar
        }
        assertThat(r.get("importadas").asInt()).isEqualTo(2);
        assertThat(r.get("duplicadas").asInt()).isEqualTo(1);
        assertThat(r.get("resumosIgnorados").asInt()).isEqualTo(1);
        assertThat(r.get("ultimoNsu").asText()).isEqualTo("000000000000004");
        assertThat(CHAMADAS).hasSize(2);
        assertThat(CHAMADAS.get(0)).isEqualTo(new Chamada(CNPJ, "SP", "0", true));                     // a primeira parte do zero, e a chamada passou pelo agente
        assertThat(CHAMADAS.get(1).nsu()).isEqualTo("000000000000002");                                  // a seguinte continua de onde parou

        JsonNode notas = corpo(json, ler(mvc, e.gestor(), "/api/clientes/" + cli + "/xml"));
        assertThat(notas).hasSize(2);
        assertThat(notas.get(0).get("origem").asText()).isEqualTo("SEFAZ");
        assertThat(notas.get(0).get("tipoOperacao").asText()).isEqualTo("ENTRADA");                     // a empresa é a destinatária
        assertThat(corpo(json, ler(mvc, e.admin(), "/api/auditoria")).toString()).contains("CERTIFICADO_USADO").contains("Consulta de notas na SEFAZ");

        // nada novo: guarda a espera de 1 hora e recusa nova consulta até lá
        ROTEIRO.add(new SefazDfeConnector.Resposta("137", "Nenhum documento localizado", "000000000000004", "000000000000004", List.of()));
        JsonNode vazio;
        try (var agente = agenteAtendendo(cen.agente())) {
            vazio = corpo(json, enviar(mvc, e.gestor(), "/api/clientes/" + cli + "/sefaz/consultar", "{}").andExpect(status().isOk()));
        }
        assertThat(vazio.get("status").asText()).isEqualTo("SEM_DOCUMENTOS");
        JsonNode estado = corpo(json, ler(mvc, e.gestor(), "/api/clientes/" + cli + "/sefaz").andExpect(status().isOk()));
        assertThat(estado.get("habilitado").asBoolean()).isTrue();
        assertThat(estado.get("proximaConsulta").isNull()).isFalse();
        String msg = corpo(json, enviar(mvc, e.gestor(), "/api/clientes/" + cli + "/sefaz/consultar", "{}").andExpect(status().isUnprocessableEntity())).get("erro").asText();
        assertThat(msg).contains("esperar");
        assertThat(CHAMADAS).hasSize(3);                                                                  // a terceira consulta nem chegou à SEFAZ
    }

    @Test
    void semCertificadoSemCnpjPermissoesEIsolamento() throws Exception {
        Empresa e = empresa(mvc, json);
        long semCert = cliente(mvc, json, e.gestor(), "91", "Sem " + e.s(), "{\"cnpj\":\"" + CNPJ + "\"}");
        long semCnpj = cliente(mvc, json, e.gestor(), "92", "Sem2 " + e.s(), null);
        assertThat(corpo(json, enviar(mvc, e.gestor(), "/api/clientes/" + semCert + "/sefaz/consultar", "{}").andExpect(status().isUnprocessableEntity())).get("erro").asText()).contains("certificado digital");
        assertThat(corpo(json, enviar(mvc, e.gestor(), "/api/clientes/" + semCnpj + "/sefaz/consultar", "{}").andExpect(status().isUnprocessableEntity())).get("erro").asText()).contains("CNPJ");
        enviar(mvc, e.usuario(), "/api/clientes/" + semCert + "/sefaz/consultar", "{}").andExpect(status().isForbidden());
        ler(mvc, e.usuario(), "/api/clientes/" + semCert + "/sefaz").andExpect(status().isForbidden());
        Empresa outra = empresa(mvc, json);
        enviar(mvc, outra.admin(), "/api/clientes/" + semCert + "/sefaz/consultar", "{}").andExpect(status().isNotFound());
    }

    @Test
    void erroDaSefazFicaRegistradoESemEsperaSeRespostaVaziaForDiferente() throws Exception {
        Empresa e = empresa(mvc, json);
        Cenario cen = empresaComCertificado(e, "93");
        ROTEIRO.clear();
        ROTEIRO.add(new SefazDfeConnector.Resposta("656", "Consumo Indevido", "0", "0", List.of()));
        JsonNode r;
        try (var agente = agenteAtendendo(cen.agente())) {
            r = corpo(json, enviar(mvc, e.gestor(), "/api/clientes/" + cen.cli() + "/sefaz/consultar", "{}").andExpect(status().isOk()));
        }
        assertThat(r.get("status").asText()).isEqualTo("ESPERAR");
        assertThat(r.get("mensagem").asText()).contains("656");
    }

    @Autowired br.com.viriato.agente.AgenteRepository agentes;

    @Test
    void agenteDesconectadoOuQueNaoRespondeNaoDerrubaOSistema() throws Exception {
        Empresa e = empresa(mvc, json);
        Cenario cen = empresaComCertificado(e, "94");
        CHAMADAS.clear(); ROTEIRO.clear();

        // o agente não aparece há muito tempo: a consulta nem começa e o texto diz o que fazer
        var ag = agentes.findAll().stream().filter(a -> a.getNome().equals("PC do gestor") && a.getTenantId() != null).reduce((x, y) -> y).orElseThrow();
        ag.setUltimoContato(java.time.Instant.now().minusSeconds(600));
        agentes.save(ag);
        String msg = corpo(json, enviar(mvc, e.gestor(), "/api/clientes/" + cen.cli() + "/sefaz/consultar", "{}").andExpect(status().isUnprocessableEntity())).get("erro").asText();
        assertThat(msg).contains("agente desconectado");
        assertThat(CHAMADAS).isEmpty();

        // conectado, mas ninguém busca o trabalho (programa travado): a espera termina e o erro fica registrado
        Agentes.contato(mvc, cen.agente(), "{}").andExpect(status().isOk());
        JsonNode r = corpo(json, enviar(mvc, e.gestor(), "/api/clientes/" + cen.cli() + "/sefaz/consultar", "{}").andExpect(status().isOk()));
        assertThat(r.get("status").asText()).isEqualTo("ERRO");
        assertThat(r.get("mensagem").asText()).contains("não respondeu a tempo");
        assertThat(corpo(json, ler(mvc, e.gestor(), "/api/clientes/" + cen.cli() + "/sefaz")).get("status").asText()).isEqualTo("ERRO");
    }
}
