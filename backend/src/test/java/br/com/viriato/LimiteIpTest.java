package br.com.viriato;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Limite por endereço de origem nas rotas públicas (3 por minuto neste teste). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "viriato.seguranca.limite-ip.maximo=3")
class LimiteIpTest {
    @Autowired MockMvc mvc;

    private int tentar(String ip) throws Exception {
        return mvc.perform(post("/api/auth/login").with(rq -> { rq.setRemoteAddr(ip); return rq; }).contentType(MediaType.APPLICATION_JSON)
                .content("{\"nome\":\"ninguem\",\"senha\":\"errada123\"}")).andReturn().getResponse().getStatus();
    }

    @Test
    void depoisDoLimiteResponde429ComRetryAfter() throws Exception {
        for (int i = 0; i < 3; i++) assertThat(tentar("10.0.0.1")).isEqualTo(401);
        var r = mvc.perform(post("/api/auth/login").with(rq -> { rq.setRemoteAddr("10.0.0.1"); return rq; }).contentType(MediaType.APPLICATION_JSON)
                .content("{\"nome\":\"ninguem\",\"senha\":\"errada123\"}")).andExpect(status().is(429)).andReturn().getResponse();
        assertThat(r.getHeader("Retry-After")).isNotNull();
        assertThat(r.getContentAsString()).contains("LIMITE");
    }

    @Test
    void cadaEnderecoTemASuaContagem() throws Exception {
        for (int i = 0; i < 3; i++) tentar("10.0.0.2");
        assertThat(tentar("10.0.0.2")).isEqualTo(429);
        assertThat(tentar("10.0.0.3")).isEqualTo(401);   // outro endereço ainda não atingiu o limite
    }

    @Test
    void saudeELeiturasNaoContam() throws Exception {
        for (int i = 0; i < 10; i++) mvc.perform(get("/api/auth/saude").with(rq -> { rq.setRemoteAddr("10.0.0.4"); return rq; })).andExpect(status().isOk());
    }
}
