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

/** Entradas e tentativas falhas ficam na auditoria com o endereço e o navegador de quem tentou. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuditoriaDeAcessoTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void loginComSucessoEFalhaGravamOrigem() throws Exception {
        String s = UUID.randomUUID().toString().substring(0, 6);
        String nome = "Adm " + s;
        String token = json.readTree(mvc.perform(post("/api/auth/registrar-empresa").contentType(MediaType.APPLICATION_JSON)
                .content("{\"empresa\":\"E\",\"nome\":\"" + nome + "\",\"email\":\"a-" + s + "@t.com\",\"senha\":\"senha12345\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)).get("token").asText();
        mvc.perform(post("/api/auth/login").header("User-Agent", "Navegador-Teste/1.0").with(r -> { r.setRemoteAddr("203.0.113.7"); return r; })
                .contentType(MediaType.APPLICATION_JSON).content("{\"nome\":\"" + nome + "\",\"senha\":\"errada-errada\"}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").header("User-Agent", "Navegador-Teste/1.0").with(r -> { r.setRemoteAddr("203.0.113.7"); return r; })
                .contentType(MediaType.APPLICATION_JSON).content("{\"nome\":\"" + nome + "\",\"senha\":\"senha12345\"}")).andExpect(status().isOk());

        JsonNode lista = json.readTree(mvc.perform(get("/api/auditoria").header("Authorization", "Bearer " + token)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        boolean falha = false, ok = false;
        for (JsonNode r : lista) {
            if (!"203.0.113.7".equals(r.path("ip").asText())) continue;
            assertThat(r.get("userAgent").asText()).isEqualTo("Navegador-Teste/1.0");
            if (r.get("acao").asText().equals("LOGIN_FALHA")) falha = true;
            if (r.get("acao").asText().equals("LOGIN")) ok = true;
        }
        assertThat(falha).as("tentativa falha registrada").isTrue();
        assertThat(ok).as("entrada registrada").isTrue();
    }

    @Test
    void nomeInexistenteNaoGravaNada() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"nome\":\"fantasma-" + UUID.randomUUID() + "\",\"senha\":\"qualquer123\"}"))
                .andExpect(status().isUnauthorized());
    }
}
