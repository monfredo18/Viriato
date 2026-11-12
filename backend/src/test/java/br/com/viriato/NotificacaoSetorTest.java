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

    record C(String admin, String gestorEng, String gestorFin, String u1, String u2, long adminId, long u1Id, long u2Id, long eng, long fin, long entrega, long empresa) {}

    private JsonNode corpo(ResultActions r) throws Exception { return json.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)); }
    private ResultActions enviar(String t, String url, String b) throws Exception {
        return mvc.perform(post(url).header("Authorization", "Bearer " + t).contentType(MediaType.APPLICATION_JSON).content(b));
    }
    private JsonNode consultar(String t, String url) throws Exception { return corpo(mvc.perform(get(url).header("Authorization", "Bearer " + t)).andExpect(status().isOk())); }
    private String login(String e) throws Exception {
        return TestesAuth.entrar(mvc, json, e);
    }
    private long usuario(String admin, String nome, String email, String papel, long setor) throws Exception {
        return corpo(enviar(admin, "/api/usuarios", "{\"nome\":\"" + nome + "\",\"email\":\"" + email + "\",\"senha\":\"senha12345\",\"papel\":\"" + papel
                + "\",\"setorId\":" + setor + "}").andExpect(status().isCreated())).get("id").asLong();
    }

    private C cenario() throws Exception {
        String s = UUID.randomUUID().toString();
        var reg = corpo(mvc.perform(post("/api/auth/registrar-empresa").contentType(MediaType.APPLICATION_JSON)
                .content("{\"empresa\":\"E\",\"nome\":\"Adm Teste\",\"email\":\"a-" + s + "@t.com\",\"senha\":\"senha12345\"}")).andExpect(status().isCreated()));
        String admin = reg.get("token").asText();
        long entrega = consultar(admin, "/api/setores").get(0).get("entregas").get(0).get("id").asLong();
        long eng = corpo(enviar(admin, "/api/setores", "{\"nome\":\"Engenharia\",\"entregaIds\":[" + entrega + "]}").andExpect(status().isCreated())).get("id").asLong();
        long fin = corpo(enviar(admin, "/api/setores", "{\"nome\":\"Financeiro\",\"entregaIds\":[" + entrega + "]}").andExpect(status().isCreated())).get("id").asLong();
        usuario(admin, "Gestor Eng", "ge-" + s + "@t.com", "GESTOR", eng);
        usuario(admin, "Gestor Fin", "gf-" + s + "@t.com", "GESTOR", fin);
        long u1 = usuario(admin, "Ursula Eng", "u1-" + s + "@t.com", "USUARIO", eng);
        long u2 = usuario(admin, "Ubiratan Fin", "u2-" + s + "@t.com", "USUARIO", fin);
        long emp = corpo(enviar(admin, "/api/clientes", "{\"numero\":\"1042\",\"nome\":\"Alfa Engenharia\"}").andExpect(status().isCreated())).get("id").asLong();
        return new C(admin, login("ge-" + s + "@t.com"), login("gf-" + s + "@t.com"), login("u1-" + s + "@t.com"), login("u2-" + s + "@t.com"),
                reg.get("usuario").get("id").asLong(), u1, u2, eng, fin, entrega, emp);
    }

    private long processo(C c, String token, long setor, long resp) throws Exception {
        return corpo(enviar(token, "/api/processos", "{\"clienteId\":" + c.empresa() + ",\"entregaId\":" + c.entrega() + ",\"setorId\":" + setor
                + ",\"responsavelId\":" + resp + "}").andExpect(status().isCreated())).get("id").asLong();
    }

    private int quantas(String token) throws Exception { return consultar(token, "/api/notificacoes").size(); }

    @Test
    void movimentacaoAvisaSoOGestorDoSetor_comONomeDeQuemFez() throws Exception {
        C c = cenario();
        long p = processo(c, c.admin(), c.eng(), c.u1Id());               // o administrador abre o processo de Engenharia
        JsonNode aberto = consultar(c.gestorEng(), "/api/notificacoes").get(0);
        assertThat(aberto.get("titulo").asText()).isEqualTo("Novo processo aberto - Adm Teste");
        long antes = quantas(c.gestorEng());

        // o funcionário (responsável) move o processo: quem recebe é o gestor do setor
        long proxima = consultar(c.u1(), "/api/processos/" + p).get("proximasEtapas").get(0).get("id").asLong();
        enviar(c.u1(), "/api/processos/" + p + "/avancar", "{\"etapaId\":" + proxima + "}").andExpect(status().isOk());
        JsonNode n = consultar(c.gestorEng(), "/api/notificacoes").get(0);
        assertThat(n.get("titulo").asText()).isEqualTo("Processo movido para Análise - Ursula Eng");
        assertThat(n.get("empresaNome").asText()).isEqualTo("Alfa Engenharia");
        assertThat(n.get("entregaNome").asText()).isEqualTo("Atendimento geral");
        assertThat(n.get("criadoEm").asText()).isNotBlank();
        assertThat(quantas(c.gestorEng())).isEqualTo(antes + 1);

        // ninguém mais é avisado: nem o próprio funcionário, nem outro setor, nem o administrador
        assertThat(quantas(c.u1())).isZero();
        assertThat(quantas(c.u2())).isZero();
        assertThat(quantas(c.gestorFin())).isZero();
        assertThat(quantas(c.admin())).isZero();
    }

    @Test
    void comentarioAtribuicaoECancelamentoTambemVaoSoParaOGestorDoSetor() throws Exception {
        C c = cenario();
        long p = processo(c, c.admin(), c.eng(), c.u1Id());
        enviar(c.u1(), "/api/processos/" + p + "/comentarios", "{\"texto\":\"Falta o laudo\"}").andExpect(status().isCreated());
        JsonNode com = consultar(c.gestorEng(), "/api/notificacoes").get(0);
        assertThat(com.get("titulo").asText()).isEqualTo("Novo comentário - Ursula Eng");
        assertThat(com.get("mensagem").asText()).isEqualTo("Falta o laudo");

        long u3 = usuario(c.admin(), "Ubirajara Eng", "u3-" + UUID.randomUUID() + "@t.com", "USUARIO", c.eng());
        mvc.perform(put("/api/processos/" + p + "/responsavel").header("Authorization", "Bearer " + c.admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"responsavelId\":" + u3 + "}")).andExpect(status().isOk());
        assertThat(consultar(c.gestorEng(), "/api/notificacoes").get(0).get("titulo").asText()).isEqualTo("Processo atribuído a Ubirajara Eng - Adm Teste");

        enviar(c.admin(), "/api/processos/" + p + "/cancelar", "{\"motivo\":\"desistiu\"}").andExpect(status().isOk());
        assertThat(consultar(c.gestorEng(), "/api/notificacoes").get(0).get("titulo").asText()).isEqualTo("Processo cancelado - Adm Teste");

        assertThat(quantas(c.gestorFin())).isZero();                      // setor do processo é Engenharia
        assertThat(quantas(c.u1())).isZero();
        assertThat(quantas(c.u2())).isZero();                             // nem funcionário de outro setor
    }

    @Test
    void cadaSetorSoVeOsAvisosDoProprioSetor_eOGestorNaoRecebeDeSiMesmo() throws Exception {
        C c = cenario();
        processo(c, c.admin(), c.fin(), c.u2Id());
        assertThat(quantas(c.gestorFin())).isEqualTo(1);
        assertThat(quantas(c.gestorEng())).isZero();
        // o gestor abre um processo do próprio setor: não avisa a si mesmo
        processo(c, c.gestorEng(), c.eng(), c.u1Id());
        assertThat(quantas(c.gestorEng())).isZero();
        // sem gestor no setor, ninguém é avisado e nada quebra
        long semGestor = corpo(enviar(c.admin(), "/api/setores", "{\"nome\":\"Sem gestor\",\"entregaIds\":[" + c.entrega() + "]}").andExpect(status().isCreated())).get("id").asLong();
        processo(c, c.admin(), semGestor, c.adminId());
        assertThat(quantas(c.admin())).isZero();
    }
}
