package br.com.viriato;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** A lista de usuários: administrador vê todos; gestor e usuário, só o próprio setor e nunca administradores. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ListaDeUsuariosPorSetorTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private JsonNode corpo(ResultActions r) throws Exception { return json.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)); }
    private ResultActions enviar(String t, String url, String b) throws Exception {
        return mvc.perform(post(url).header("Authorization", "Bearer " + t).contentType(MediaType.APPLICATION_JSON).content(b));
    }
    private void criar(String admin, String nome, String papel, Long setor) throws Exception {
        enviar(admin, "/api/usuarios", "{\"nome\":\"" + nome + "\",\"senha\":\"senha12345\",\"papel\":\"" + papel + "\"" + (setor == null ? "" : ",\"setorId\":" + setor) + "}").andExpect(status().isCreated());
    }
    private List<String> nomes(String t) throws Exception {
        List<String> l = new ArrayList<>();
        for (JsonNode u : corpo(mvc.perform(get("/api/usuarios").header("Authorization", "Bearer " + t)).andExpect(status().isOk()))) l.add(u.get("nome").asText());
        return l;
    }

    @Test
    void gestorEUsuarioVeemSoOProprioSetorSemAdministradores() throws Exception {
        String s = UUID.randomUUID().toString().substring(0, 6);
        String admin = corpo(mvc.perform(post("/api/auth/registrar-empresa").contentType(MediaType.APPLICATION_JSON)
                .content("{\"empresa\":\"E\",\"nome\":\"Adm " + s + "\",\"email\":\"a-" + s + "@t.com\",\"senha\":\"senha12345\"}")).andExpect(status().isCreated())).get("token").asText();
        long geral = corpo(mvc.perform(get("/api/setores").header("Authorization", "Bearer " + admin))).get(0).get("id").asLong();
        long fin = corpo(enviar(admin, "/api/setores", "{\"nome\":\"Financeiro\",\"entregaIds\":[]}").andExpect(status().isCreated())).get("id").asLong();
        criar(admin, "Gestor G " + s, "GESTOR", geral);
        criar(admin, "Ana G " + s, "USUARIO", geral);
        criar(admin, "Bia G " + s, "USUARIO", geral);
        criar(admin, "Caio F " + s, "USUARIO", fin);
        criar(admin, "Adm Dois " + s, "ADMIN", geral);       // administrador com o mesmo setor também não aparece
        criar(admin, "Sem Setor " + s, "USUARIO", null);

        List<String> doAdmin = nomes(admin);
        assertThat(doAdmin).anyMatch(n -> n.startsWith("Caio F")).anyMatch(n -> n.startsWith("Adm Dois")).anyMatch(n -> n.startsWith("Sem Setor"));

        for (String quem : new String[]{TestesAuth.entrar(mvc, json, "Gestor G " + s), TestesAuth.entrar(mvc, json, "Ana G " + s)}) {
            List<String> v = nomes(quem);
            assertThat(v).hasSize(3);
            assertThat(v).allMatch(n -> n.startsWith("Gestor G") || n.startsWith("Ana G") || n.startsWith("Bia G"));
        }
        // sem setor: só enxerga a si mesmo
        assertThat(nomes(TestesAuth.entrar(mvc, json, "Sem Setor " + s))).hasSize(1).allMatch(n -> n.startsWith("Sem Setor"));
        // do Financeiro: só Caio
        assertThat(nomes(TestesAuth.entrar(mvc, json, "Caio F " + s))).hasSize(1);
    }
}
