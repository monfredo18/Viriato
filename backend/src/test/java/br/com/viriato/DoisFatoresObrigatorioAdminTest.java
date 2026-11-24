package br.com.viriato;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.viriato.seguranca.Totp;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Com SEGURANCA_EXIGIR_2FA_ADMIN, o administrador só configura a verificação em duas etapas até ativá-la. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "viriato.seguranca.exigir-2fa-admin=true")
class DoisFatoresObrigatorioAdminTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private JsonNode corpo(org.springframework.test.web.servlet.ResultActions r) throws Exception { return json.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)); }

    @Test
    void administradorSemDuasEtapasFicaRestritoAteAtivar() throws Exception {
        String s = UUID.randomUUID().toString().substring(0, 6);
        String t = corpo(mvc.perform(post("/api/auth/registrar-empresa").contentType(MediaType.APPLICATION_JSON)
                .content("{\"empresa\":\"E\",\"nome\":\"Adm " + s + "\",\"email\":\"a-" + s + "@t.com\",\"senha\":\"senha12345\"}")).andExpect(status().isCreated())).get("token").asText();
        String h = "Bearer " + t;
        assertThat(corpo(mvc.perform(get("/api/me").header("Authorization", h))).get("deveAtivarDoisFatores").asBoolean()).isTrue();
        var negado = mvc.perform(get("/api/setores").header("Authorization", h)).andExpect(status().isForbidden()).andReturn().getResponse().getContentAsString();
        assertThat(negado).contains("ATIVAR_2FA");

        String segredo = corpo(mvc.perform(post("/api/me/2fa/iniciar").header("Authorization", h))).get("segredo").asText();
        String codigo = Totp.codigo(segredo, Totp.passoAtual());
        var ativado = corpo(mvc.perform(post("/api/me/2fa/ativar").header("Authorization", h).contentType(MediaType.APPLICATION_JSON).content("{\"codigo\":\"" + codigo + "\"}")).andExpect(status().isOk()));
        assertThat(ativado.get("deveAtivarDoisFatores").asBoolean()).isFalse();
        mvc.perform(get("/api/setores").header("Authorization", h)).andExpect(status().isOk());
    }
}
