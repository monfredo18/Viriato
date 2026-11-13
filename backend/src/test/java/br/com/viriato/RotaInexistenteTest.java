package br.com.viriato;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** Rota que não existe responde 404, e método errado, 405 (antes virava 500 "Erro interno"). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RotaInexistenteTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void rotaInexistenteEhNaoEncontradaEMetodoErradoEhNaoPermitido() throws Exception {
        String s = UUID.randomUUID().toString().substring(0, 8);
        mvc.perform(post("/api/auth/registrar-empresa").contentType(MediaType.APPLICATION_JSON)
                .content("{\"empresa\":\"E\",\"nome\":\"Adm-" + s + "\",\"email\":\"a-" + s + "@t.com\",\"senha\":\"senha12345\"}")).andExpect(status().isCreated());
        String t = TestesAuth.entrar(mvc, json, "a-" + s + "@t.com");
        mvc.perform(get("/api/configuracao/email").header("Authorization", "Bearer " + t)).andExpect(status().isNotFound()).andExpect(jsonPath("$.erro").value("Recurso não encontrado"));
        mvc.perform(get("/api/rota-que-nunca-existiu").header("Authorization", "Bearer " + t)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/processos").header("Authorization", "Bearer " + t)).andExpect(status().isMethodNotAllowed());
    }
}
