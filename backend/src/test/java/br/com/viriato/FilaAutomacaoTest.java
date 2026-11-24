package br.com.viriato;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.viriato.automacao.AutomacaoExecucao;
import br.com.viriato.automacao.AutomacaoExecucaoRepository;
import br.com.viriato.automacao.AutomacaoExecutor;
import br.com.viriato.automacao.StatusExecucao;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntUnaryOperator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Fila persistida das automações: tentativas com espera crescente, falha definitiva e retomada depois de queda do servidor. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = { "viriato.automacao.retry.tentativas=3", "viriato.automacao.retry.base-segundos=0" })
class FilaAutomacaoTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AutomacaoExecutor executor;
    @Autowired AutomacaoExecucaoRepository execucoes;

    record C(String admin, long processo, long analise) {}

    private JsonNode corpo(ResultActions r) throws Exception { return json.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)); }
    private ResultActions enviar(String t, String url, String b) throws Exception {
        return mvc.perform(post(url).header("Authorization", "Bearer " + t).contentType(MediaType.APPLICATION_JSON).content(b));
    }
    private JsonNode ler(String t, String url) throws Exception { return corpo(mvc.perform(get(url).header("Authorization", "Bearer " + t)).andExpect(status().isOk())); }

    private HttpServer servidor(AtomicInteger chamadas, IntUnaryOperator codigoPorChamada) throws Exception {
        HttpServer srv = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        srv.createContext("/hook", ex -> {
            ex.getRequestBody().readAllBytes();
            ex.sendResponseHeaders(codigoPorChamada.applyAsInt(chamadas.incrementAndGet()), -1);
            ex.close();
        });
        srv.start();
        return srv;
    }

    /** Empresa com um webhook na etapa Análise; o processo já foi aberto e o webhook ainda não rodou. */
    private C cenario(HttpServer srv) throws Exception {
        String s = UUID.randomUUID().toString().substring(0, 8);
        enviar("", "/api/auth/registrar-empresa", "{\"empresa\":\"E\",\"nome\":\"Adm-" + s + "\",\"email\":\"a-" + s + "@t.com\",\"senha\":\"senha12345\"}");
        String admin = TestesAuth.entrar(mvc, json, "a-" + s + "@t.com");
        JsonNode geral = ler(admin, "/api/setores").get(0);
        long setor = geral.get("id").asLong(), entrega = geral.get("entregas").get(0).get("id").asLong();
        long eu = ler(admin, "/api/me").get("id").asLong();
        long cliente = corpo(enviar(admin, "/api/clientes", "{\"numero\":\"10\",\"nome\":\"Alfa\"}").andExpect(status().isCreated())).get("id").asLong();
        JsonNode fluxo = ler(admin, "/api/fluxos").get(0);
        long analise = fluxo.get("etapas").get(1).get("id").asLong();
        enviar(admin, "/api/fluxos/" + fluxo.get("id").asLong() + "/automacoes", "{\"evento\":\"ENTRAR_ETAPA\",\"etapaId\":" + analise
                + ",\"tipo\":\"WEBHOOK\",\"url\":\"http://127.0.0.1:" + srv.getAddress().getPort() + "/hook\"}").andExpect(status().isCreated());
        long p = corpo(enviar(admin, "/api/processos", "{\"clienteId\":" + cliente + ",\"entregaId\":" + entrega + ",\"setorId\":" + setor + ",\"responsavelId\":" + eu + "}")
                .andExpect(status().isCreated())).get("id").asLong();
        return new C(admin, p, analise);
    }

    private JsonNode ultima(C c) throws Exception { return ler(c.admin(), "/api/processos/" + c.processo() + "/automacoes").get(0); }
    private void mover(C c) throws Exception { enviar(c.admin(), "/api/processos/" + c.processo() + "/avancar", "{\"etapaId\":" + c.analise() + "}").andExpect(status().isOk()); }

    @Test
    void falhaTemporariaVoltaParaAFilaEDaCertoNaTerceiraTentativa() throws Exception {
        AtomicInteger n = new AtomicInteger();
        HttpServer srv = servidor(n, i -> i < 3 ? 503 : 200);
        try {
            C c = cenario(srv);
            mover(c);                                                    // tentativa 1 (logo depois do commit): 503
            JsonNode x = ultima(c);
            assertThat(x.get("status").asText()).isEqualTo("PENDENTE");
            assertThat(x.get("mensagem").asText()).contains("Tentativa 1 de 3").contains("Resposta HTTP 503");
            assertThat(executor.processarPendentes()).isGreaterThanOrEqualTo(1);       // tentativa 2: 503
            assertThat(ultima(c).get("status").asText()).isEqualTo("PENDENTE");
            assertThat(executor.processarPendentes()).isGreaterThanOrEqualTo(1);       // tentativa 3: 200
            assertThat(ultima(c).get("status").asText()).isEqualTo("OK");
            assertThat(n.get()).isEqualTo(3);
            executor.processarPendentes();   // nada mais para esta execução
        } finally { srv.stop(0); }
    }

    @Test
    void esgotadasAsTentativasFicaFalhaComOMotivo() throws Exception {
        AtomicInteger n = new AtomicInteger();
        HttpServer srv = servidor(n, i -> 500);
        try {
            C c = cenario(srv);
            mover(c);
            executor.processarPendentes();
            executor.processarPendentes();
            JsonNode x = ultima(c);
            assertThat(x.get("status").asText()).isEqualTo("FALHA");
            assertThat(x.get("mensagem").asText()).contains("Resposta HTTP 500").contains("após 3 tentativas");
            assertThat(n.get()).isEqualTo(3);
            executor.processarPendentes();   // nada mais para esta execução
        } finally { srv.stop(0); }
    }

    @Test
    void respostaDeErroDoOutroLadoNaoEhRepetida() throws Exception {
        AtomicInteger n = new AtomicInteger();
        HttpServer srv = servidor(n, i -> 404);
        try {
            C c = cenario(srv);
            mover(c);
            assertThat(ultima(c).get("status").asText()).isEqualTo("FALHA");
            assertThat(n.get()).isEqualTo(1);
        } finally { srv.stop(0); }
    }

    @Test
    void execucaoQueFicouProcessandoPorQuedaDoServidorEhRetomada() throws Exception {
        AtomicInteger n = new AtomicInteger();
        HttpServer srv = servidor(n, i -> 200);
        try {
            C c = cenario(srv);
            // simula: o servidor caiu logo depois do commit, com a execução reservada e nunca concluída
            mover(c);
            AutomacaoExecucao x = execucoes.findById(ultima(c).get("id").asLong()).orElseThrow();
            x.setStatus(StatusExecucao.PROCESSANDO);
            x.setIniciadoEm(Instant.now().minusSeconds(3600));
            x.setTentativas(0);
            execucoes.save(x);
            int antes = n.get();
            assertThat(executor.processarPendentes()).isGreaterThanOrEqualTo(1);
            assertThat(ultima(c).get("status").asText()).isEqualTo("OK");
            assertThat(n.get()).isEqualTo(antes + 1);
        } finally { srv.stop(0); }
    }

    @Test
    void naFilaDoBancoCadaRegraTemUmaLinhaPorEvento() throws Exception {
        AtomicInteger n = new AtomicInteger();
        HttpServer srv = servidor(n, i -> 200);
        try {
            C c = cenario(srv);
            mover(c);
            JsonNode lista = ler(c.admin(), "/api/processos/" + c.processo() + "/automacoes");
            assertThat(lista).hasSize(1);
            assertThat(lista.get(0).get("status").asText()).isEqualTo("OK");
        } finally { srv.stop(0); }
    }
}
