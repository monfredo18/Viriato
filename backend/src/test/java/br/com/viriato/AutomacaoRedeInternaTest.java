package br.com.viriato;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Com a configuração de produção (rede interna proibida), a API recusa webhooks para endereços internos ou sem https. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "viriato.automacao.permitir-rede-interna=false")
class AutomacaoRedeInternaTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void recusaDestinosInternos_eAceitaHttpsPublico() throws Exception {
        String s = UUID.randomUUID().toString().substring(0, 8);
        mvc.perform(post("/api/auth/registrar-empresa").contentType(MediaType.APPLICATION_JSON)
                .content("{\"empresa\":\"E\",\"nome\":\"Adm-" + s + "\",\"email\":\"a-" + s + "@t.com\",\"senha\":\"senha12345\"}")).andExpect(status().isCreated());
        String admin = TestesAuth.entrar(mvc, json, "a-" + s + "@t.com");
        long fluxo = json.readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/fluxos")
                .header("Authorization", "Bearer " + admin)).andReturn().getResponse().getContentAsString()).get(0).get("id").asLong();
        String url = "/api/fluxos/" + fluxo + "/automacoes";
        for (String ruim : new String[]{"http://exemplo.com/x", "https://127.0.0.1/x", "https://localhost/x", "https://169.254.169.254/x", "https://10.1.2.3/x"}) {
            mvc.perform(post(url).header("Authorization", "Bearer " + admin).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"evento\":\"CANCELADO\",\"tipo\":\"WEBHOOK\",\"url\":\"" + ruim + "\"}")).andExpect(status().isUnprocessableEntity());
        }
        mvc.perform(post(url).header("Authorization", "Bearer " + admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"evento\":\"CANCELADO\",\"tipo\":\"WEBHOOK\",\"url\":\"https://hooks.exemplo.com/entrada\"}")).andExpect(status().isCreated());
    }
}
