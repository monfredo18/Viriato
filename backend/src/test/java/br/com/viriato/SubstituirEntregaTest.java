package br.com.viriato;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Apagar entrega com processos em andamento: eles passam para a entrega substituta escolhida. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SubstituirEntregaTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    record C(String admin, String gestorGeral, String usuarioGeral, long geral, long fin, long cliente, long usuarioId, long entregaPadrao) {}

    private JsonNode corpo(ResultActions r) throws Exception { return json.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)); }
    private ResultActions enviar(String t, String url, String b) throws Exception {
        return mvc.perform(post(url).header("Authorization", "Bearer " + t).contentType(MediaType.APPLICATION_JSON).content(b));
    }
    private ResultActions ler(String t, String url) throws Exception { return mvc.perform(get(url).header("Authorization", "Bearer " + t)); }
    private long criar(String admin, String nome, String papel, long setor) throws Exception {
        return corpo(enviar(admin, "/api/usuarios", "{\"nome\":\"" + nome + "\",\"senha\":\"senha12345\",\"papel\":\"" + papel + "\",\"setorId\":" + setor + "}").andExpect(status().isCreated())).get("id").asLong();
    }

    private C cenario() throws Exception {
        String s = UUID.randomUUID().toString().substring(0, 6);
        String admin = corpo(mvc.perform(post("/api/auth/registrar-empresa").contentType(MediaType.APPLICATION_JSON)
                .content("{\"empresa\":\"E\",\"nome\":\"Adm " + s + "\",\"email\":\"a-" + s + "@t.com\",\"senha\":\"senha12345\"}")).andExpect(status().isCreated())).get("token").asText();
        JsonNode setorGeral = corpo(ler(admin, "/api/setores")).get(0);
        long geral = setorGeral.get("id").asLong(), padrao = setorGeral.get("entregas").get(0).get("id").asLong();
        long fin = corpo(enviar(admin, "/api/setores", "{\"nome\":\"Financeiro\",\"entregaIds\":[]}").andExpect(status().isCreated())).get("id").asLong();
        long cliente = corpo(enviar(admin, "/api/clientes", "{\"numero\":\"7\",\"nome\":\"Alfa\"}").andExpect(status().isCreated())).get("id").asLong();
        criar(admin, "GestorG " + s, "GESTOR", geral);
        long ug = criar(admin, "UsuG " + s, "USUARIO", geral);
        return new C(admin, TestesAuth.entrar(mvc, json, "GestorG " + s), TestesAuth.entrar(mvc, json, "UsuG " + s), geral, fin, cliente, ug, padrao);
    }

    private long processo(C c, long entrega) throws Exception {
        return corpo(enviar(c.admin(), "/api/processos", "{\"clienteId\":" + c.cliente() + ",\"entregaId\":" + entrega + ",\"setorId\":" + c.geral()
                + ",\"responsavelId\":" + c.usuarioId() + "}").andExpect(status().isCreated())).get("id").asLong();
    }

    private long entregaGeral(C c, String nome) throws Exception {
        long id = corpo(enviar(c.admin(), "/api/entregas", "{\"nome\":\"" + nome + "\"}").andExpect(status().isCreated())).get("id").asLong();
        enviar(c.admin(), "/api/setores/" + c.geral(), "").andExpect(status().isMethodNotAllowed());   // (PATCH é o método certo; só confere a rota)
        mvc.perform(patch("/api/setores/" + c.geral()).header("Authorization", "Bearer " + c.admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"entregaIds\":[" + c.entregaPadrao() + "," + id + "]}")).andExpect(status().isOk());
        return id;
    }

    @Test
    void processosEmAndamentoPassamParaAEntregaEscolhida() throws Exception {
        C c = cenario();
        long nova = entregaGeral(c, "Substituta");
        long p = processo(c, c.entregaPadrao());
        JsonNode det = corpo(ler(c.admin(), "/api/processos/" + p));
        long analise = det.get("proximasEtapas").get(0).get("id").asLong();
        enviar(c.admin(), "/api/processos/" + p + "/avancar", "{\"etapaId\":" + analise + "}").andExpect(status().isOk());   // processo na etapa "Análise"

        assertThat(corpo(ler(c.admin(), "/api/entregas/" + c.entregaPadrao() + "/pendencias")).get("emAndamento").asLong()).isEqualTo(1);
        mvc.perform(delete("/api/entregas/" + c.entregaPadrao()).header("Authorization", "Bearer " + c.admin())).andExpect(status().isUnprocessableEntity());   // direto, continua bloqueado
        enviar(c.admin(), "/api/entregas/" + c.entregaPadrao() + "/apagar", "{}").andExpect(status().isUnprocessableEntity());                                  // sem substituta
        enviar(c.admin(), "/api/entregas/" + c.entregaPadrao() + "/apagar", "{\"entregaSubstitutaId\":" + c.entregaPadrao() + "}").andExpect(status().isUnprocessableEntity());   // ela mesma
        enviar(c.usuarioGeral(), "/api/entregas/" + c.entregaPadrao() + "/apagar", "{\"entregaSubstitutaId\":" + nova + "}").andExpect(status().isForbidden());

        enviar(c.admin(), "/api/entregas/" + c.entregaPadrao() + "/apagar", "{\"entregaSubstitutaId\":" + nova + "}").andExpect(status().isNoContent());
        JsonNode depois = corpo(ler(c.admin(), "/api/processos/" + p));
        assertThat(depois.get("processo").get("entregaNome").asText()).isEqualTo("Substituta");
        assertThat(depois.get("processo").get("titulo").asText()).isEqualTo("Substituta — Alfa");
        assertThat(depois.get("processo").get("etapaAtual").asText()).isEqualTo("Análise");        // etapa de mesmo nome no fluxo novo
        assertThat(depois.get("processo").get("status").asText()).isEqualTo("ABERTO");
        assertThat(depois.get("historico").toString()).contains("ENTREGA_SUBSTITUIDA").contains("apagada");
        StringBuilder entregas = new StringBuilder();
        for (JsonNode e : corpo(ler(c.admin(), "/api/entregas"))) entregas.append(e.get("nome").asText()).append('|');
        assertThat(entregas.toString()).isEqualTo("Substituta|");
    }

    @Test
    void substitutaPrecisaSerUsavelPeloSetorDoProcesso() throws Exception {
        C c = cenario();
        long daGeral = corpo(enviar(c.gestorGeral(), "/api/entregas", "{\"nome\":\"Exclusiva Geral\"}").andExpect(status().isCreated())).get("id").asLong();
        long deOutroSetor = corpo(enviar(c.admin(), "/api/entregas", "{\"nome\":\"Do Financeiro\"}").andExpect(status().isCreated())).get("id").asLong();
        long semSetor = corpo(enviar(c.admin(), "/api/entregas", "{\"nome\":\"Que o Geral não faz\"}").andExpect(status().isCreated())).get("id").asLong();
        long p = processo(c, daGeral);
        // entrega geral que o setor não faz: recusada, e nada é apagado
        var erro = corpo(enviar(c.gestorGeral(), "/api/entregas/" + daGeral + "/apagar", "{\"entregaSubstitutaId\":" + semSetor + "}").andExpect(status().isUnprocessableEntity()));
        assertThat(erro.get("erro").asText()).contains("não faz");
        assertThat(corpo(ler(c.admin(), "/api/processos/" + p)).get("processo").get("entregaNome").asText()).isEqualTo("Exclusiva Geral");
        // a padrão (que o setor faz) serve; o processo continua com o mesmo responsável e número
        enviar(c.gestorGeral(), "/api/entregas/" + daGeral + "/apagar", "{\"entregaSubstitutaId\":" + c.entregaPadrao() + "}").andExpect(status().isNoContent());
        JsonNode depois = corpo(ler(c.admin(), "/api/processos/" + p)).get("processo");
        assertThat(depois.get("entregaNome").asText()).isEqualTo("Atendimento geral");
        assertThat(depois.get("responsavelNome").asText()).startsWith("UsuG");
        assertThat(depois.get("numero").asText()).isEqualTo("7");
        assertThat(deOutroSetor).isPositive();
    }

    @Test
    void semProcessoEmAndamentoApagaDireto() throws Exception {
        C c = cenario();
        long nova = corpo(enviar(c.gestorGeral(), "/api/entregas", "{\"nome\":\"Sem uso\"}").andExpect(status().isCreated())).get("id").asLong();
        assertThat(corpo(ler(c.gestorGeral(), "/api/entregas/" + nova + "/pendencias")).get("emAndamento").asLong()).isZero();
        enviar(c.gestorGeral(), "/api/entregas/" + nova + "/apagar", "{}").andExpect(status().isNoContent());
    }
}
