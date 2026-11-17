package br.com.viriato;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Entra com a senha inicial "senha12345" e, se o sistema exigir, define a senha própria (como no primeiro acesso). */
final class TestesAuth {
    static final String SENHA_INICIAL = "senha12345";
    static final String SENHA_PROPRIA = "minha-senha-propria-1";

    private TestesAuth() {}

    static String entrar(MockMvc mvc, ObjectMapper json, String usuario) throws Exception {
        JsonNode sessao = json.readTree(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"nome\":\"" + usuario + "\",\"senha\":\"" + SENHA_INICIAL + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        String token = sessao.get("token").asText();
        if (sessao.get("usuario").get("deveTrocarSenha").asBoolean()) {
            mvc.perform(put("/api/me/senha").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"novaSenha\":\"" + SENHA_PROPRIA + "\"}")).andExpect(status().isOk());
        }
        return token;
    }

    /** Cria uma entrega (com fluxo próprio, etapas padrão) dentro do setor e devolve o id. Cada setor tem as suas entregas. */
    static long entregaDoSetor(MockMvc mvc, ObjectMapper json, String admin, long setorId, String nome) throws Exception {
        return json.readTree(mvc.perform(post("/api/entregas").header("Authorization", "Bearer " + admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"nome\":\"" + nome + "\",\"setorId\":" + setorId + "}")).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)).get("id").asLong();
    }

    /** A entrega do setor (a primeira que ele tiver); se não tiver nenhuma, cria "Atendimento geral" nele. */
    static long entregaPadraoDoSetor(MockMvc mvc, ObjectMapper json, String admin, long setorId) throws Exception {
        var lista = json.readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/entregas").header("Authorization", "Bearer " + admin))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        for (var e : lista) if (!e.get("setorDonoId").isNull() && e.get("setorDonoId").asLong() == setorId) return e.get("id").asLong();
        return entregaDoSetor(mvc, json, admin, setorId, "Atendimento " + setorId);
    }
}
