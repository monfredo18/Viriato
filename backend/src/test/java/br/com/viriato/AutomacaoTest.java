package br.com.viriato;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.viriato.automacao.UrlWebhook;
import br.com.viriato.automacao.WebhookClient;
import br.com.viriato.documento.ArmazenamentoArquivos;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Automações por etapa: configuração, documento gerado, webhook assinado e isolamento de falhas. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AutomacaoTest.StorageFake.class)
class AutomacaoTest {

    @TestConfiguration
    static class StorageFake {
        @Bean @Primary
        ArmazenamentoArquivos storageEmMemoria() {
            Map<String, byte[]> mem = new ConcurrentHashMap<>();
            return new ArmazenamentoArquivos() {
                public void salvar(String c, InputStream in, long t, String ct) {
                    try { mem.put(c, in.readAllBytes()); } catch (Exception e) { throw new RuntimeException(e); }
                }
                public InputStream abrir(String c) { return new ByteArrayInputStream(mem.get(c)); }
                public void remover(String c) { mem.remove(c); }
            };
        }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    record C(String admin, String gestor, String usuario, String gestorOutroSetor, long usuarioId, long clienteId, long setorId, long entregaId,
             long fluxoId, long abertura, long analise, long revisao, long concluido) {}

    record Recebido(String corpo, String evento, String assinatura) {}

    private JsonNode corpo(ResultActions r) throws Exception { return json.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)); }
    private ResultActions enviar(String t, String url, String b) throws Exception {
        return mvc.perform(post(url).header("Authorization", "Bearer " + t).contentType(MediaType.APPLICATION_JSON).content(b));
    }
    private ResultActions ler(String t, String url) throws Exception { return mvc.perform(get(url).header("Authorization", "Bearer " + t)); }
    private JsonNode consultar(String t, String url) throws Exception { return corpo(ler(t, url).andExpect(status().isOk())); }
    private long usuario(String admin, String nome, String papel, long setor) throws Exception {
        return corpo(enviar(admin, "/api/usuarios", "{\"nome\":\"" + nome + "\",\"senha\":\"senha12345\",\"papel\":\"" + papel + "\",\"setorId\":" + setor + "}")
                .andExpect(status().isCreated())).get("id").asLong();
    }

    private C cenario() throws Exception {
        String s = UUID.randomUUID().toString().substring(0, 8);
        mvc.perform(post("/api/auth/registrar-empresa").contentType(MediaType.APPLICATION_JSON)
                .content("{\"empresa\":\"E\",\"nome\":\"Adm-" + s + "\",\"email\":\"a-" + s + "@t.com\",\"senha\":\"senha12345\"}")).andExpect(status().isCreated());
        String admin = TestesAuth.entrar(mvc, json, "a-" + s + "@t.com");
        JsonNode geral = consultar(admin, "/api/setores").get(0);
        long setor = geral.get("id").asLong(), entrega = geral.get("entregas").get(0).get("id").asLong();
        long outro = corpo(enviar(admin, "/api/setores", "{\"nome\":\"Outro\",\"entregaIds\":[" + entrega + "]}").andExpect(status().isCreated())).get("id").asLong();
        usuario(admin, "Ges-" + s, "GESTOR", setor);
        long u = usuario(admin, "Usu-" + s, "USUARIO", setor);
        usuario(admin, "GesO-" + s, "GESTOR", outro);
        long cliente = corpo(enviar(admin, "/api/clientes", "{\"numero\":\"10\",\"nome\":\"Alfa Ltda\"}").andExpect(status().isCreated())).get("id").asLong();
        JsonNode fluxo = consultar(admin, "/api/fluxos").get(0);
        JsonNode et = fluxo.get("etapas");
        return new C(admin, TestesAuth.entrar(mvc, json, "Ges-" + s), TestesAuth.entrar(mvc, json, "Usu-" + s), TestesAuth.entrar(mvc, json, "GesO-" + s), u, cliente, setor, entrega,
                fluxo.get("id").asLong(), et.get(0).get("id").asLong(), et.get(1).get("id").asLong(), et.get(2).get("id").asLong(), et.get(3).get("id").asLong());
    }

    private long regra(C c, String corpoJson) throws Exception {
        return corpo(enviar(c.gestor(), "/api/fluxos/" + c.fluxoId() + "/automacoes", corpoJson).andExpect(status().isCreated())).get("id").asLong();
    }
    private String entrando(long etapa, String resto) { return "{\"evento\":\"ENTRAR_ETAPA\",\"etapaId\":" + etapa + "," + resto + "}"; }

    private long processo(C c) throws Exception {
        return corpo(enviar(c.admin(), "/api/processos", "{\"clienteId\":" + c.clienteId() + ",\"entregaId\":" + c.entregaId() + ",\"setorId\":" + c.setorId()
                + ",\"responsavelId\":" + c.usuarioId() + "}").andExpect(status().isCreated())).get("id").asLong();
    }
    private ResultActions avancar(String t, long p, long etapa) throws Exception { return enviar(t, "/api/processos/" + p + "/avancar", "{\"etapaId\":" + etapa + "}"); }
    private JsonNode execucoes(String t, long p) throws Exception { return consultar(t, "/api/processos/" + p + "/automacoes"); }

    /** Servidor HTTP local que guarda o que recebe e responde com o código dado. */
    private HttpServer servidor(List<Recebido> recebidos, int codigo) throws Exception {
        HttpServer srv = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        srv.createContext("/hook", ex -> {
            String corpo = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            recebidos.add(new Recebido(corpo, ex.getRequestHeaders().getFirst("X-Viriato-Evento"), ex.getRequestHeaders().getFirst("X-Viriato-Assinatura")));
            ex.sendResponseHeaders(codigo, -1);
            ex.close();
        });
        srv.start();
        return srv;
    }
    private String urlDe(HttpServer srv) { return "http://127.0.0.1:" + srv.getAddress().getPort() + "/hook"; }

    @Test
    void configuracaoTemPermissaoEValidacoes() throws Exception {
        C c = cenario();
        String url = "/api/fluxos/" + c.fluxoId() + "/automacoes";
        enviar(c.usuario(), url, entrando(c.analise(), "\"tipo\":\"GERAR_DOCUMENTO\"")).andExpect(status().isForbidden());
        ler(c.usuario(), url).andExpect(status().isForbidden());                       // nem consulta: pode ter endereços e segredos
        enviar(c.gestor(), url, entrando(c.analise(), "\"tipo\":\"EMAIL_EMPRESA\"")).andExpect(status().isBadRequest());   // o envio de e-mail não existe mais
        enviar(c.gestor(), url, entrando(c.analise(), "\"tipo\":\"WEBHOOK\",\"url\":\"ftp://x.com/y\"")).andExpect(status().isUnprocessableEntity());
        enviar(c.gestor(), url, entrando(c.analise(), "\"tipo\":\"WEBHOOK\"")).andExpect(status().isUnprocessableEntity());
        enviar(c.gestor(), url, "{\"evento\":\"ENTRAR_ETAPA\",\"tipo\":\"GERAR_DOCUMENTO\"}").andExpect(status().isUnprocessableEntity());   // sem etapa
        enviar(c.gestor(), url, entrando(999999, "\"tipo\":\"GERAR_DOCUMENTO\"")).andExpect(status().isNotFound());
        enviar(c.gestor(), url, "{\"tipo\":\"GERAR_DOCUMENTO\"}").andExpect(status().isUnprocessableEntity());                                  // sem evento

        long doc = regra(c, entrando(c.analise(), "\"tipo\":\"GERAR_DOCUMENTO\""));
        enviar(c.gestor(), url, entrando(c.analise(), "\"tipo\":\"GERAR_DOCUMENTO\"")).andExpect(status().isUnprocessableEntity());   // só um por etapa
        long hook = regra(c, entrando(c.revisao(), "\"tipo\":\"WEBHOOK\",\"url\":\"http://127.0.0.1:9/hook\",\"segredo\":\"abc\""));
        long cancel = regra(c, "{\"evento\":\"CANCELADO\",\"etapaId\":" + c.analise() + ",\"tipo\":\"GERAR_DOCUMENTO\"}");   // etapa é ignorada no cancelamento

        JsonNode lista = consultar(c.gestor(), url);
        assertThat(lista).hasSize(3);
        JsonNode h = null, x = null;
        for (JsonNode r : lista) { if (r.get("id").asLong() == hook) h = r; if (r.get("id").asLong() == cancel) x = r; }
        assertThat(h.get("temSegredo").asBoolean()).isTrue();
        assertThat(h.toString()).doesNotContain("abc");                                  // o segredo nunca volta
        assertThat(h.get("etapaNome").asText()).isEqualTo("Revisão");
        assertThat(x.get("etapaId").isNull()).isTrue();

        // desativar, manter o segredo e depois apagar
        mvc.perform(patch(url + "/" + hook).header("Authorization", "Bearer " + c.admin()).contentType(MediaType.APPLICATION_JSON).content("{\"ativo\":false}")).andExpect(status().isOk());
        JsonNode depois = consultar(c.admin(), url);
        for (JsonNode r : depois) if (r.get("id").asLong() == hook) { assertThat(r.get("ativo").asBoolean()).isFalse(); assertThat(r.get("temSegredo").asBoolean()).isTrue(); }
        mvc.perform(patch(url + "/" + hook).header("Authorization", "Bearer " + c.admin()).contentType(MediaType.APPLICATION_JSON).content("{\"segredo\":\"\"}")).andExpect(status().isOk());
        for (JsonNode r : consultar(c.admin(), url)) if (r.get("id").asLong() == hook) assertThat(r.get("temSegredo").asBoolean()).isFalse();   // vazio apaga
        mvc.perform(delete(url + "/" + doc).header("Authorization", "Bearer " + c.usuario())).andExpect(status().isForbidden());
        mvc.perform(delete(url + "/" + doc).header("Authorization", "Bearer " + c.gestor())).andExpect(status().isNoContent());
        assertThat(consultar(c.gestor(), url)).hasSize(2);
    }

    @Test
    void geraAFichaEmPdfQuandoOProcessoEntraNaEtapa_eQuandoEleNasce() throws Exception {
        C c = cenario();
        regra(c, entrando(c.abertura(), "\"tipo\":\"GERAR_DOCUMENTO\""));
        regra(c, entrando(c.analise(), "\"tipo\":\"GERAR_DOCUMENTO\""));
        long p = processo(c);   // criar já entra na etapa inicial

        JsonNode docs = consultar(c.usuario(), "/api/processos/" + p + "/documentos");
        assertThat(docs).hasSize(1);
        assertThat(docs.get(0).get("nome").asText()).isEqualTo("Ficha-10-Abertura.pdf");
        assertThat(docs.get(0).get("automatico").asBoolean()).isTrue();
        assertThat(docs.get(0).get("enviadoPor").isNull()).isTrue();

        avancar(c.usuario(), p, c.analise()).andExpect(status().isOk());
        docs = consultar(c.usuario(), "/api/processos/" + p + "/documentos");
        assertThat(docs).hasSize(2);   // a regra de Análise gerou outra ficha; entrar em Revisão (sem regra) não gera nada
        avancar(c.usuario(), p, c.revisao()).andExpect(status().isOk());
        assertThat(consultar(c.usuario(), "/api/processos/" + p + "/documentos")).hasSize(2);

        byte[] pdf = mvc.perform(get("/api/documentos/" + docs.get(0).get("id").asLong() + "/download").header("Authorization", "Bearer " + c.usuario()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
        assertThat(pdf.length).isGreaterThan(1000);

        JsonNode log = execucoes(c.usuario(), p);
        assertThat(log).hasSize(2);
        assertThat(log.get(0).get("status").asText()).isEqualTo("OK");
        assertThat(log.get(0).get("tipo").asText()).isEqualTo("GERAR_DOCUMENTO");
        assertThat(log.get(0).get("etapa").asText()).isEqualTo("Análise");
    }

    @Test
    void webhookEnviaJsonAssinado_eRegistraSucessoEFalha() throws Exception {
        C c = cenario();
        List<Recebido> recebidos = new CopyOnWriteArrayList<>();
        HttpServer srv = servidor(recebidos, 200);
        try {
            regra(c, entrando(c.analise(), "\"tipo\":\"WEBHOOK\",\"url\":\"" + urlDe(srv) + "\",\"segredo\":\"s3cr3t\""));
            regra(c, "{\"evento\":\"CANCELADO\",\"tipo\":\"WEBHOOK\",\"url\":\"" + urlDe(srv) + "\"}");
            long p = processo(c);
            assertThat(recebidos).isEmpty();                                   // criar entra em Abertura: sem regra lá
            avancar(c.usuario(), p, c.analise()).andExpect(status().isOk());

            assertThat(recebidos).hasSize(1);
            Recebido r = recebidos.get(0);
            assertThat(r.evento()).isEqualTo("ENTROU_NA_ETAPA");
            assertThat(r.assinatura()).isEqualTo("sha256=" + WebhookClient.assinar("s3cr3t", r.corpo()));   // quem recebe confere com o segredo
            JsonNode corpo = json.readTree(r.corpo());
            assertThat(corpo.get("evento").asText()).isEqualTo("ENTROU_NA_ETAPA");
            assertThat(corpo.get("processo").get("numero").asText()).isEqualTo("10");
            assertThat(corpo.get("processo").get("empresa").asText()).isEqualTo("Alfa Ltda");
            assertThat(corpo.get("processo").get("etapa").asText()).isEqualTo("Análise");
            assertThat(corpo.get("processo").get("situacao").asText()).isEqualTo("Em andamento");
            assertThat(execucoes(c.usuario(), p).get(0).get("status").asText()).isEqualTo("OK");

            // cancelar dispara a regra de cancelamento (sem segredo, sem assinatura)
            enviar(c.gestor(), "/api/processos/" + p + "/cancelar", "{\"motivo\":\"x\"}").andExpect(status().isOk());
            assertThat(recebidos).hasSize(2);
            assertThat(recebidos.get(1).evento()).isEqualTo("CANCELADO");
            assertThat(recebidos.get(1).assinatura()).isNull();
        } finally {
            srv.stop(0);
        }
    }

    @Test
    void falhaDeIntegracaoNaoDesfazOMovimento_eRegraDesativadaNaoRoda() throws Exception {
        C c = cenario();
        List<Recebido> recebidos = new CopyOnWriteArrayList<>();
        HttpServer erro = servidor(recebidos, 500);
        try {
            long r500 = regra(c, entrando(c.analise(), "\"tipo\":\"WEBHOOK\",\"url\":\"" + urlDe(erro) + "\""));
            // porta onde ninguém escuta: falha de conexão
            regra(c, entrando(c.analise(), "\"tipo\":\"WEBHOOK\",\"url\":\"http://127.0.0.1:9/hook\""));
            long desligada = regra(c, entrando(c.analise(), "\"tipo\":\"WEBHOOK\",\"url\":\"" + urlDe(erro) + "\""));
            mvc.perform(patch("/api/fluxos/" + c.fluxoId() + "/automacoes/" + desligada).header("Authorization", "Bearer " + c.gestor())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"ativo\":false}")).andExpect(status().isOk());

            long p = processo(c);
            avancar(c.usuario(), p, c.analise()).andExpect(status().isOk());   // o movimento acontece mesmo com as integrações falhando
            assertThat(consultar(c.usuario(), "/api/processos/" + p).get("processo").get("etapaAtual").asText()).isEqualTo("Análise");

            assertThat(recebidos).hasSize(1);                                  // a desativada não rodou
            JsonNode log = execucoes(c.usuario(), p);
            assertThat(log).hasSize(2);
            assertThat(log.get(0).get("status").asText()).isEqualTo("FALHA");   // conexão recusada
            assertThat(log.get(1).get("status").asText()).isEqualTo("FALHA");
            assertThat(log.get(1).get("mensagem").asText()).isEqualTo("Resposta HTTP 500");
            assertThat(r500).isPositive();
        } finally {
            erro.stop(0);
        }
    }

    @Test
    void historicoDeExecucaoSegueAVisibilidadeDoProcesso() throws Exception {
        C c = cenario();
        regra(c, entrando(c.abertura(), "\"tipo\":\"GERAR_DOCUMENTO\""));
        long p = processo(c);
        assertThat(execucoes(c.usuario(), p)).hasSize(1);
        ler(c.gestorOutroSetor(), "/api/processos/" + p + "/automacoes").andExpect(status().isNotFound());   // outro setor não enxerga
    }

    @Test
    void apagarOFluxoLevaAsAutomacoesJunto() throws Exception {
        C c = cenario();
        long fluxo = corpo(enviar(c.admin(), "/api/fluxos", "{\"nome\":\"Temp\",\"etapas\":[{\"nome\":\"A\",\"inicial\":true},{\"nome\":\"B\",\"finalEtapa\":true}]}")
                .andExpect(status().isCreated())).get("id").asLong();
        long etapaA = consultar(c.admin(), "/api/fluxos/" + fluxo).get("etapas").get(0).get("id").asLong();
        enviar(c.admin(), "/api/fluxos/" + fluxo + "/automacoes", entrando(etapaA, "\"tipo\":\"GERAR_DOCUMENTO\"")).andExpect(status().isCreated());
        enviar(c.admin(), "/api/fluxos/" + fluxo + "/automacoes", "{\"evento\":\"CANCELADO\",\"tipo\":\"GERAR_DOCUMENTO\"}").andExpect(status().isCreated());
        mvc.perform(delete("/api/fluxos/" + fluxo).header("Authorization", "Bearer " + c.admin())).andExpect(status().isNoContent());
        ler(c.admin(), "/api/fluxos/" + fluxo + "/automacoes").andExpect(status().isNotFound());
    }

    @Test
    void protecaoContraRedeInterna() {
        for (String ruim : new String[]{"http://exemplo.com/x", "ftp://exemplo.com/x", "https://localhost/x", "https://127.0.0.1/x", "https://10.0.0.5/x",
                "https://192.168.1.10/x", "https://172.16.0.1/x", "https://169.254.169.254/latest/meta-data", "https://[::1]/x", "https://100.64.0.1/x",
                "https://0.0.0.0/x", "https://servico.local/x", "https://usuario:senha@exemplo.com/x", "https://", "exemplo.com/x", " "}) {
            assertThatThrownBy(() -> UrlWebhook.validar(ruim, false)).as(ruim).isInstanceOf(br.com.viriato.comum.NegocioException.class);
        }
        assertThat(UrlWebhook.validar("https://hooks.exemplo.com/entrada?token=abc", false).getHost()).isEqualTo("hooks.exemplo.com");
        assertThat(UrlWebhook.validar("https://203.0.113.10:8443/x", false).getPort()).isEqualTo(8443);
        // só em desenvolvimento/teste a rede interna e o http são aceitos
        assertThat(UrlWebhook.validar("http://127.0.0.1:8080/x", true).getHost()).isEqualTo("127.0.0.1");
    }
}
