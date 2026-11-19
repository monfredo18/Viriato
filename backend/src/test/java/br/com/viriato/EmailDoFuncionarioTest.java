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

/** O e-mail do funcionário é obrigatório ao criar; administrador e gestor (do próprio setor) editam; só eles o veem. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EmailDoFuncionarioTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private JsonNode corpo(ResultActions r) throws Exception { return json.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)); }
    private ResultActions enviar(String t, String url, String b) throws Exception {
        return mvc.perform(post(url).header("Authorization", "Bearer " + t).contentType(MediaType.APPLICATION_JSON).content(b));
    }
    private ResultActions alterar(String t, String url, String b) throws Exception {
        return mvc.perform(patch(url).header("Authorization", "Bearer " + t).contentType(MediaType.APPLICATION_JSON).content(b));
    }
    private JsonNode lista(String t) throws Exception { return corpo(mvc.perform(get("/api/usuarios").header("Authorization", "Bearer " + t)).andExpect(status().isOk())); }
    private String emailDe(String t, long id) throws Exception {
        for (JsonNode u : lista(t)) if (u.get("id").asLong() == id) return u.get("email").isNull() ? null : u.get("email").asText();
        throw new AssertionError("não achei " + id);
    }

    @Test
    void emailObrigatorioNaCriacaoEditavelPorQuemGerencia() throws Exception {
        String s = UUID.randomUUID().toString().substring(0, 6);
        String admin = corpo(mvc.perform(post("/api/auth/registrar-empresa").contentType(MediaType.APPLICATION_JSON)
                .content("{\"empresa\":\"E\",\"nome\":\"Adm " + s + "\",\"email\":\"a-" + s + "@t.com\",\"senha\":\"senha12345\"}")).andExpect(status().isCreated())).get("token").asText();
        long geral = corpo(mvc.perform(get("/api/setores").header("Authorization", "Bearer " + admin))).get(0).get("id").asLong();
        long outro = corpo(enviar(admin, "/api/setores", "{\"nome\":\"Outro\"}").andExpect(status().isCreated())).get("id").asLong();
        enviar(admin, "/api/usuarios", "{\"nome\":\"Gestor " + s + "\",\"email\":\"g-" + s + "@t.com\",\"senha\":\"senha12345\",\"papel\":\"GESTOR\",\"setorId\":" + geral + "}").andExpect(status().isCreated());
        String gestor = TestesAuth.entrar(mvc, json, "Gestor " + s);

        // sem e-mail, em branco ou malformado: recusado (a pessoa não é criada)
        enviar(gestor, "/api/usuarios", "{\"nome\":\"Sem " + s + "\",\"senha\":\"senha12345\",\"papel\":\"USUARIO\"}").andExpect(status().isBadRequest());
        enviar(gestor, "/api/usuarios", "{\"nome\":\"Sem " + s + "\",\"email\":\"  \",\"senha\":\"senha12345\",\"papel\":\"USUARIO\"}").andExpect(status().isBadRequest());
        enviar(gestor, "/api/usuarios", "{\"nome\":\"Sem " + s + "\",\"email\":\"isso-nao-e-email\",\"senha\":\"senha12345\",\"papel\":\"USUARIO\"}").andExpect(status().isBadRequest());
        // com e-mail: criado; o e-mail volta em maiúsculas/minúsculas normalizado; repetido é recusado
        long ana = corpo(enviar(gestor, "/api/usuarios", "{\"nome\":\"Ana " + s + "\",\"email\":\"ANA-" + s + "@Empresa.com\",\"senha\":\"senha12345\",\"papel\":\"USUARIO\"}").andExpect(status().isCreated())).get("id").asLong();
        assertThat(emailDe(gestor, ana)).isEqualTo("ana-" + s + "@empresa.com");
        enviar(gestor, "/api/usuarios", "{\"nome\":\"Bia " + s + "\",\"email\":\"ana-" + s + "@empresa.com\",\"senha\":\"senha12345\",\"papel\":\"USUARIO\"}").andExpect(status().isUnprocessableEntity());

        // o gestor edita o e-mail de quem é do setor dele
        alterar(gestor, "/api/usuarios/" + ana, "{\"email\":\"nova-" + s + "@empresa.com\"}").andExpect(status().isOk());
        assertThat(emailDe(gestor, ana)).isEqualTo("nova-" + s + "@empresa.com");
        alterar(gestor, "/api/usuarios/" + ana, "{\"email\":\"\"}").andExpect(status().isUnprocessableEntity());                 // não pode apagar
        alterar(gestor, "/api/usuarios/" + ana, "{\"email\":\"lixo\"}").andExpect(status().isUnprocessableEntity());
        alterar(gestor, "/api/usuarios/" + ana, "{\"email\":\"g-" + s + "@t.com\"}").andExpect(status().isUnprocessableEntity());   // já é de outra pessoa

        // fora do alcance do gestor: outro setor, outro papel
        long dOutro = corpo(enviar(admin, "/api/usuarios", "{\"nome\":\"Cris " + s + "\",\"email\":\"c-" + s + "@t.com\",\"senha\":\"senha12345\",\"papel\":\"USUARIO\",\"setorId\":" + outro + "}").andExpect(status().isCreated())).get("id").asLong();
        alterar(gestor, "/api/usuarios/" + dOutro, "{\"email\":\"x-" + s + "@t.com\"}").andExpect(status().isForbidden());
        // o administrador edita qualquer um
        alterar(admin, "/api/usuarios/" + dOutro, "{\"email\":\"cris-" + s + "@t.com\"}").andExpect(status().isOk());
        assertThat(emailDe(admin, dOutro)).isEqualTo("cris-" + s + "@t.com");

        // usuário comum só enxerga o próprio e-mail (e não edita nenhum)
        String usuarioAna = TestesAuth.entrar(mvc, json, "Ana " + s);
        assertThat(emailDe(usuarioAna, ana)).isEqualTo("nova-" + s + "@empresa.com");
        for (JsonNode u : lista(usuarioAna)) if (u.get("id").asLong() != ana) assertThat(u.get("email").isNull()).isTrue();
        alterar(usuarioAna, "/api/usuarios/" + ana, "{\"email\":\"x@y.com\"}").andExpect(status().isForbidden());
    }
}
