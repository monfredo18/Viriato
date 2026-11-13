package br.com.viriato;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Chat de ajuda: a pessoa conversa com os administradores da empresa; ninguém enxerga a conversa dos outros. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AjudaChatTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    record Cenario(String admin, String admin2, String ana, String bia, long anaId, long conversaDeAna) {}

    private JsonNode corpo(ResultActions r) throws Exception { return json.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)); }
    private ResultActions enviar(String t, String url, String b) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post(url).header("Authorization", "Bearer " + t).contentType(MediaType.APPLICATION_JSON).content(b));
    }
    private ResultActions ler(String t, String url) throws Exception { return mvc.perform(MockMvcRequestBuilders.get(url).header("Authorization", "Bearer " + t)); }
    private String texto(String t) { return "{\"texto\":\"" + t + "\"}"; }
    private long naoLidas(String t) throws Exception { return corpo(ler(t, "/api/ajuda/nao-lidas").andExpect(status().isOk())).get("total").asLong(); }

    private long usuario(String admin, String nome, String papel) throws Exception {
        return corpo(enviar(admin, "/api/usuarios", "{\"nome\":\"" + nome + "\",\"senha\":\"senha12345\",\"papel\":\"" + papel + "\"}").andExpect(status().isCreated())).get("id").asLong();
    }

    private Cenario cenario() throws Exception {
        String s = UUID.randomUUID().toString().substring(0, 8);
        var reg = corpo(mvc.perform(MockMvcRequestBuilders.post("/api/auth/registrar-empresa").contentType(MediaType.APPLICATION_JSON)
                .content("{\"empresa\":\"E\",\"nome\":\"Adm " + s + "\",\"email\":\"a-" + s + "@t.com\",\"senha\":\"senha12345\"}")).andExpect(status().isCreated()));
        String admin = reg.get("token").asText();
        long anaId = usuario(admin, "Ana " + s, "USUARIO");
        usuario(admin, "Bia " + s, "GESTOR");
        usuario(admin, "Adm2 " + s, "ADMIN");
        return new Cenario(admin, TestesAuth.entrar(mvc, json, "Adm2 " + s), TestesAuth.entrar(mvc, json, "Ana " + s), TestesAuth.entrar(mvc, json, "Bia " + s), anaId, 0);
    }

    @Test
    void pessoaPedeAjudaEOsAdministradoresRespondem() throws Exception {
        var c = cenario();
        assertThat(ler(c.ana(), "/api/ajuda/minha").andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).isEqualTo("[]");
        assertThat(naoLidas(c.admin())).isZero();

        enviar(c.ana(), "/api/ajuda/minha", texto("Não consigo mover o processo")).andExpect(status().isCreated());
        assertThat(naoLidas(c.admin())).isEqualTo(1);
        assertThat(naoLidas(c.admin2())).isEqualTo(1);          // todos os administradores veem o aviso
        assertThat(naoLidas(c.ana())).isZero();

        JsonNode lista = corpo(ler(c.admin(), "/api/ajuda/conversas").andExpect(status().isOk()));
        assertThat(lista).hasSize(1);
        assertThat(lista.get(0).get("pessoa").asText()).startsWith("Ana");
        assertThat(lista.get(0).get("naoLidas").asInt()).isEqualTo(1);
        assertThat(lista.get(0).get("ultimaTexto").asText()).isEqualTo("Não consigo mover o processo");
        long id = lista.get(0).get("id").asLong();

        JsonNode aberta = corpo(ler(c.admin(), "/api/ajuda/conversas/" + id).andExpect(status().isOk()));
        assertThat(aberta.get("mensagens")).hasSize(1);
        assertThat(aberta.get("mensagens").get(0).get("deAdmin").asBoolean()).isFalse();
        assertThat(naoLidas(c.admin())).isZero();               // abrir marca como visto

        enviar(c.admin2(), "/api/ajuda/conversas/" + id + "/mensagens", texto("Já liberei, tente de novo")).andExpect(status().isCreated());
        assertThat(naoLidas(c.ana())).isEqualTo(1);
        JsonNode minha = corpo(ler(c.ana(), "/api/ajuda/minha").andExpect(status().isOk()));
        assertThat(minha).hasSize(2);
        assertThat(minha.get(1).get("deAdmin").asBoolean()).isTrue();
        assertThat(minha.get(1).get("autorNome").asText()).startsWith("Adm2");
        assertThat(naoLidas(c.ana())).isZero();
    }

    @Test
    void ninguemEnxergaConversaAlheiaEAdminNaoAbreConversaPropria() throws Exception {
        var c = cenario();
        enviar(c.ana(), "/api/ajuda/minha", texto("Oi")).andExpect(status().isCreated());
        long id = corpo(ler(c.admin(), "/api/ajuda/conversas")).get(0).get("id").asLong();

        // pessoa comum (e até gestor) não usa as rotas dos administradores
        ler(c.bia(), "/api/ajuda/conversas").andExpect(status().isForbidden());
        ler(c.bia(), "/api/ajuda/conversas/" + id).andExpect(status().isForbidden());
        enviar(c.bia(), "/api/ajuda/conversas/" + id + "/mensagens", texto("intrometido")).andExpect(status().isForbidden());
        // cada um só vê a própria conversa
        assertThat(corpo(ler(c.bia(), "/api/ajuda/minha")).size()).isZero();
        // administrador atende, não abre conversa
        ler(c.admin(), "/api/ajuda/minha").andExpect(status().isForbidden());
        enviar(c.admin(), "/api/ajuda/minha", texto("oi")).andExpect(status().isForbidden());

        // administrador de OUTRA empresa não encontra a conversa
        String outra = UUID.randomUUID().toString().substring(0, 8);
        String adminOutra = corpo(mvc.perform(MockMvcRequestBuilders.post("/api/auth/registrar-empresa").contentType(MediaType.APPLICATION_JSON)
                .content("{\"empresa\":\"Outra\",\"nome\":\"Adm " + outra + "\",\"email\":\"o-" + outra + "@t.com\",\"senha\":\"senha12345\"}")).andExpect(status().isCreated())).get("token").asText();
        ler(adminOutra, "/api/ajuda/conversas/" + id).andExpect(status().isNotFound());
        enviar(adminOutra, "/api/ajuda/conversas/" + id + "/mensagens", texto("x")).andExpect(status().isNotFound());
        assertThat(corpo(ler(adminOutra, "/api/ajuda/conversas"))).isEmpty();
        assertThat(naoLidas(adminOutra)).isZero();
    }

    @Test
    void mensagemVaziaOuGrandeDemaisERecusada() throws Exception {
        var c = cenario();
        enviar(c.ana(), "/api/ajuda/minha", texto("   ")).andExpect(status().isBadRequest());
        enviar(c.ana(), "/api/ajuda/minha", "{}").andExpect(status().isBadRequest());
        enviar(c.ana(), "/api/ajuda/minha", texto("a".repeat(2001))).andExpect(status().isBadRequest());
        enviar(c.ana(), "/api/ajuda/minha", texto("a".repeat(2000))).andExpect(status().isCreated());
        ler(c.ana(), "/api/ajuda/minha").andExpect(status().isOk());
    }

    @Test
    void apagarAPessoaApagaAConversaDela() throws Exception {
        var c = cenario();
        enviar(c.ana(), "/api/ajuda/minha", texto("Preciso de ajuda")).andExpect(status().isCreated());
        assertThat(corpo(ler(c.admin(), "/api/ajuda/conversas"))).hasSize(1);
        mvc.perform(MockMvcRequestBuilders.delete("/api/usuarios/" + c.anaId()).header("Authorization", "Bearer " + c.admin())).andExpect(status().isNoContent());
        assertThat(corpo(ler(c.admin(), "/api/ajuda/conversas"))).isEmpty();
        assertThat(naoLidas(c.admin())).isZero();
    }
}
