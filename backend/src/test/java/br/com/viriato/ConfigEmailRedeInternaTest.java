package br.com.viriato;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
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

/** Com a configuração de produção, o servidor de e-mail não pode ser um endereço da rede interna. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "viriato.automacao.permitir-rede-interna=false")
class ConfigEmailRedeInternaTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void recusaServidorInternoEAceitaEnderecoPublico() throws Exception {
        String s = UUID.randomUUID().toString().substring(0, 8);
        mvc.perform(post("/api/auth/registrar-empresa").contentType(MediaType.APPLICATION_JSON)
                .content("{\"empresa\":\"E\",\"nome\":\"Adm-" + s + "\",\"email\":\"a-" + s + "@t.com\",\"senha\":\"senha12345\"}")).andExpect(status().isCreated());
        String admin = TestesAuth.entrar(mvc, json, "a-" + s + "@t.com");
        for (String host : new String[]{"localhost", "127.0.0.1", "10.0.0.5", "192.168.0.10", "169.254.169.254", "mailpit.local"}) {
            mvc.perform(put("/api/configuracao/email").header("Authorization", "Bearer " + admin).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"host\":\"" + host + "\",\"porta\":587,\"seguranca\":\"STARTTLS\",\"remetente\":\"a@b.com\"}")).andExpect(status().isUnprocessableEntity());
        }
        // nome que não existe: a mensagem pede para conferir o endereço
        String r = mvc.perform(put("/api/configuracao/email").header("Authorization", "Bearer " + admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"host\":\"servidor-que-nao-existe.invalid\",\"porta\":587,\"seguranca\":\"STARTTLS\",\"remetente\":\"a@b.com\"}"))
                .andExpect(status().isUnprocessableEntity()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        org.assertj.core.api.Assertions.assertThat(r).contains("Não encontrei o servidor");
        mvc.perform(put("/api/configuracao/email").header("Authorization", "Bearer " + admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"host\":\"203.0.113.10\",\"porta\":587,\"seguranca\":\"STARTTLS\",\"remetente\":\"a@b.com\"}")).andExpect(status().isOk());
    }
}
