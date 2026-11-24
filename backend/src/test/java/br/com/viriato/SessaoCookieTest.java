package br.com.viriato;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Sessão em cookie HttpOnly, SameSite=Strict, com o código anti-CSRF; o Bearer fica desligado (padrão de produção). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "viriato.seguranca.aceitar-bearer=false")
class SessaoCookieTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private MvcResult registrar(String s) throws Exception {
        return mvc.perform(post("/api/auth/registrar-empresa").contentType(MediaType.APPLICATION_JSON)
                .content("{\"empresa\":\"E\",\"nome\":\"Adm " + s + "\",\"email\":\"a-" + s + "@t.com\",\"senha\":\"senha12345\"}"))
                .andExpect(status().isCreated()).andReturn();
    }

    private static String valor(MvcResult r, String nome) {
        return r.getResponse().getHeaders("Set-Cookie").stream().filter(h -> h.startsWith(nome + "=")).findFirst()
                .map(h -> h.substring(nome.length() + 1, h.indexOf(';'))).orElseThrow();
    }

    @Test
    void loginDevolveCookieHttpOnlySemTokenNoCorpo() throws Exception {
        String s = UUID.randomUUID().toString().substring(0, 6);
        MvcResult r = registrar(s);
        List<String> cookies = r.getResponse().getHeaders("Set-Cookie");
        String sessao = cookies.stream().filter(h -> h.startsWith("VIRIATO_SESSAO=")).findFirst().orElseThrow();
        assertThat(sessao).contains("HttpOnly").contains("SameSite=Strict").contains("Path=/api");
        String csrf = cookies.stream().filter(h -> h.startsWith("XSRF-TOKEN=")).findFirst().orElseThrow();
        assertThat(csrf).doesNotContain("HttpOnly").contains("SameSite=Strict");
        JsonNode corpo = json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(corpo.get("token") == null || corpo.get("token").isNull()).isTrue();
    }

    @Test
    void cookieAutenticaELeituraNaoExigeCsrf() throws Exception {
        MvcResult r = registrar(UUID.randomUUID().toString().substring(0, 6));
        mvc.perform(get("/api/me").cookie(new Cookie("VIRIATO_SESSAO", valor(r, "VIRIATO_SESSAO")))).andExpect(status().isOk());
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void alteracaoViaCookieExigeCsrfIgualAoCookie() throws Exception {
        MvcResult r = registrar(UUID.randomUUID().toString().substring(0, 6));
        Cookie sessao = new Cookie("VIRIATO_SESSAO", valor(r, "VIRIATO_SESSAO"));
        Cookie csrf = new Cookie("XSRF-TOKEN", valor(r, "XSRF-TOKEN"));
        String corpo = "{\"nome\":\"Setor X\",\"entregaIds\":[]}";
        mvc.perform(post("/api/setores").cookie(sessao).contentType(MediaType.APPLICATION_JSON).content(corpo)).andExpect(status().isForbidden());
        mvc.perform(post("/api/setores").cookie(sessao, csrf).header("X-XSRF-TOKEN", "outro").contentType(MediaType.APPLICATION_JSON).content(corpo)).andExpect(status().isForbidden());
        mvc.perform(post("/api/setores").cookie(sessao, csrf).header("X-XSRF-TOKEN", csrf.getValue()).contentType(MediaType.APPLICATION_JSON).content(corpo)).andExpect(status().isCreated());
    }

    @Test
    void bearerNaoValeQuandoDesligado() throws Exception {
        MvcResult r = registrar(UUID.randomUUID().toString().substring(0, 6));
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + valor(r, "VIRIATO_SESSAO"))).andExpect(status().isUnauthorized());
    }

    @Test
    void sairLimpaOsCookiesEInvalidaOToken() throws Exception {
        MvcResult r = registrar(UUID.randomUUID().toString().substring(0, 6));
        Cookie sessao = new Cookie("VIRIATO_SESSAO", valor(r, "VIRIATO_SESSAO"));
        Cookie csrf = new Cookie("XSRF-TOKEN", valor(r, "XSRF-TOKEN"));
        MvcResult sair = mvc.perform(post("/api/me/sair").cookie(sessao, csrf).header("X-XSRF-TOKEN", csrf.getValue())).andExpect(status().isNoContent()).andReturn();
        assertThat(sair.getResponse().getHeaders("Set-Cookie")).anyMatch(h -> h.startsWith("VIRIATO_SESSAO=") && h.contains("Max-Age=0"));
        mvc.perform(get("/api/me").cookie(sessao)).andExpect(status().isUnauthorized());   // o token antigo foi revogado
    }

    @Test
    void cookieSecureQuandoAConexaoEHttps() throws Exception {
        String s = UUID.randomUUID().toString().substring(0, 6);
        MvcResult r = mvc.perform(post("/api/auth/registrar-empresa").secure(true).contentType(MediaType.APPLICATION_JSON)
                .content("{\"empresa\":\"E\",\"nome\":\"Adm " + s + "\",\"email\":\"a-" + s + "@t.com\",\"senha\":\"senha12345\"}")).andReturn();
        assertThat(r.getResponse().getHeaders("Set-Cookie")).anyMatch(h -> h.startsWith("VIRIATO_SESSAO=") && h.contains("Secure"));
    }
}
