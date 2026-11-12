package br.com.viriato;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Com o cadastro aberto de empresas desligado (padrão em produção), o endpoint responde 404. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "viriato.cadastro-empresa-aberto=false")
class CadastroFechadoTest {
    @Autowired MockMvc mvc;

    @Test
    void registrarEmpresaFicaIndisponivel() throws Exception {
        mvc.perform(post("/api/auth/registrar-empresa").contentType(MediaType.APPLICATION_JSON)
                .content("{\"empresa\":\"X\",\"nome\":\"Fulano\",\"email\":\"f@x.com\",\"senha\":\"senha12345\"}"))
                .andExpect(status().isNotFound());
    }
}
