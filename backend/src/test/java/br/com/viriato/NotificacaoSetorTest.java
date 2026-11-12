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

/** Notificação traz o que foi feito, empresa, entrega e autor; e nunca cruza setores. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NotificacaoSetorTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    record C(String admin, String u1, String u2, long adminId, long u1Id, long u2Id, long eng, long fin, long entrega, long empresa) {}

    private JsonNode corpo(ResultActions r) throws Exception { return json.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)); }
    private ResultActions enviar(String t, String url, String b) throws Exception {
        return mvc.perform(post(url).header("Authorization", "Bearer " + t).contentType(MediaType.APPLICATION_JSON).content(b));
    }
    private JsonNode consultar(String t, String url) throws Exception { return corpo(mvc.perform(get(url).header("Authorization", "Bearer " + t)).andExpect(status().isOk())); }
    private String login(String e) throws Exception {
        return corpo(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + e + "\",\"senha\":\"senha12345\"}")).andExpect(status().isOk())).get("token").asText();
    }

    private C cenario() throws Exception {
        String s = UUID.randomUUID().toString();
        var reg = corpo(mvc.perform(post("/api/auth/registrar-empresa").contentType(MediaType.APPLICATION_JSON)
                .content("{\"empresa\":\"E\",\"nome\":\"Adm Teste\",\"email\":\"a-" + s + "@t.com\",\"senha\":\"senha12345\"}")).andExpect(status().isCreated()));
        String admin = reg.get("token").asText();
        long entrega = consultar(admin, "/api/setores").get(0).get("entregas").get(0).get("id").asLong();
        long eng = corpo(enviar(admin, "/api/setores", "{\"nome\":\"Engenharia\",\"entregaIds\":[" + entrega + "]}").andExpect(status().isCreated())).get("id").asLong();
        long fin = corpo(enviar(admin, "/api/setores", "{\"nome\":\"Financeiro\",\"entregaIds\":[" + entrega + "]}").andExpect(status().isCreated())).get("id").asLong();
        long u1 = corpo(enviar(admin, "/api/usuarios", "{\"nome\":\"Ursula Eng\",\"email\":\"u1-" + s + "@t.com\",\"senha\":\"senha12345\",\"papel\":\"USUARIO\",\"setorId\":" + eng + "}").andExpect(status().isCreated())).get("id").asLong();
        long u2 = corpo(enviar(admin, "/api/usuarios", "{\"nome\":\"Ubiratan Fin\",\"email\":\"u2-" + s + "@t.com\",\"senha\":\"senha12345\",\"papel\":\"USUARIO\",\"setorId\":" + fin + "}").andExpect(status().isCreated())).get("id").asLong();
        long emp = corpo(enviar(admin, "/api/clientes", "{\"numero\":\"1042\",\"nome\":\"Alfa Engenharia\"}").andExpect(status().isCreated())).get("id").asLong();
        return new C(admin, login("u1-" + s + "@t.com"), login("u2-" + s + "@t.com"), reg.get("usuario").get("id").asLong(), u1, u2, eng, fin, entrega, emp);
    }

    private long processo(C c, long setor, long resp) throws Exception {
        return corpo(enviar(c.admin(), "/api/processos", "{\"clienteId\":" + c.empresa() + ",\"entregaId\":" + c.entrega() + ",\"setorId\":" + setor
                + ",\"responsavelId\":" + resp + "}").andExpect(status().isCreated())).get("id").asLong();
    }

    @Test
    void notificacaoInformaOQueFoiFeitoEmpresaEntregaAutorEHorario() throws Exception {
        C c = cenario();
        processo(c, c.eng(), c.u1Id());
        JsonNode n = consultar(c.u1(), "/api/notificacoes").get(0);
        assertThat(n.get("titulo").asText()).isEqualTo("Novo processo atribuído a você");
        assertThat(n.get("empresaNome").asText()).isEqualTo("Alfa Engenharia");
        assertThat(n.get("entregaNome").asText()).isEqualTo("Atendimento geral");
        assertThat(n.get("autorNome").asText()).isEqualTo("Adm Teste");
        assertThat(n.get("criadoEm").asText()).isNotBlank();

        long p = processo(c, c.eng(), c.u1Id());
        enviar(c.admin(), "/api/processos/" + p + "/comentarios", "{\"texto\":\"Falta o laudo\"}").andExpect(status().isCreated());
        JsonNode com = consultar(c.u1(), "/api/notificacoes").get(0);
        assertThat(com.get("titulo").asText()).startsWith("Novo comentário");
        assertThat(com.get("mensagem").asText()).isEqualTo("Falta o laudo");
        assertThat(com.get("autorNome").asText()).isEqualTo("Adm Teste");
    }

    @Test
    void setorNaoRecebeNotificacaoDeProcessoDeOutroSetor() throws Exception {
        C c = cenario();
        long p = processo(c, c.eng(), c.u2Id());                                            // responsável é do Financeiro, processo é de Engenharia
        assertThat(consultar(c.u2(), "/api/notificacoes")).isEmpty();
        enviar(c.admin(), "/api/processos/" + p + "/comentarios", "{\"texto\":\"x\"}").andExpect(status().isCreated());
        assertThat(consultar(c.u2(), "/api/notificacoes")).isEmpty();
        long q = processo(c, c.eng(), c.u1Id());                                            // reatribuir a alguém de outro setor também não avisa
        mvc.perform(put("/api/processos/" + q + "/responsavel").header("Authorization", "Bearer " + c.admin())
                .contentType(MediaType.APPLICATION_JSON).content("{\"responsavelId\":" + c.u2Id() + "}")).andExpect(status().isOk());
        assertThat(consultar(c.u2(), "/api/notificacoes")).isEmpty();
        // mesmo setor: recebe
        processo(c, c.fin(), c.u2Id());
        assertThat(consultar(c.u2(), "/api/notificacoes")).hasSize(1);
        // quem não tem setor (administrador) continua recebendo
        processo(c, c.eng(), c.adminId());
        assertThat(consultar(c.admin(), "/api/notificacoes").size()).isEqualTo(0);          // o autor não se auto-notifica
    }
}
