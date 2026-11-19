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

/** Cada setor tem as suas entregas: um setor não vê nem usa as do outro. Só o administrador cria setores. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EntregasPorSetorTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    record C(String admin, String gestorGeral, String gestorFin, String usuarioGeral, String usuarioFin,
             long geral, long fin, long fluxo, long cliente, long usuarioGeralId, long usuarioFinId) {}

    private JsonNode corpo(ResultActions r) throws Exception { return json.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)); }
    private ResultActions enviar(String t, String url, String b) throws Exception {
        return mvc.perform(post(url).header("Authorization", "Bearer " + t).contentType(MediaType.APPLICATION_JSON).content(b));
    }
    private ResultActions alterar(String t, String url, String b) throws Exception {
        return mvc.perform(patch(url).header("Authorization", "Bearer " + t).contentType(MediaType.APPLICATION_JSON).content(b));
    }
    private ResultActions ler(String t, String url) throws Exception { return mvc.perform(get(url).header("Authorization", "Bearer " + t)); }
    private long criar(String admin, String nome, String papel, long setor) throws Exception {
        return corpo(enviar(admin, "/api/usuarios", "{\"nome\":\"" + nome + "\",\"email\":\"u" + System.nanoTime() + "@t.com\",\"senha\":\"senha12345\",\"papel\":\"" + papel + "\",\"setorId\":" + setor + "}").andExpect(status().isCreated())).get("id").asLong();
    }

    private C cenario() throws Exception {
        String s = UUID.randomUUID().toString().substring(0, 6);
        String admin = corpo(mvc.perform(post("/api/auth/registrar-empresa").contentType(MediaType.APPLICATION_JSON)
                .content("{\"empresa\":\"E\",\"nome\":\"Adm " + s + "\",\"email\":\"a-" + s + "@t.com\",\"senha\":\"senha12345\"}")).andExpect(status().isCreated())).get("token").asText();
        long geral = corpo(ler(admin, "/api/setores")).get(0).get("id").asLong();
        long fin = corpo(enviar(admin, "/api/setores", "{\"nome\":\"Financeiro\"}").andExpect(status().isCreated())).get("id").asLong();
        long fluxo = corpo(ler(admin, "/api/fluxos")).get(0).get("id").asLong();
        long cliente = corpo(enviar(admin, "/api/clientes", "{\"numero\":\"7\",\"nome\":\"Alfa\"}").andExpect(status().isCreated())).get("id").asLong();
        criar(admin, "GestorG " + s, "GESTOR", geral);
        criar(admin, "GestorF " + s, "GESTOR", fin);
        long ug = criar(admin, "UsuG " + s, "USUARIO", geral);
        long uf = criar(admin, "UsuF " + s, "USUARIO", fin);
        return new C(admin, TestesAuth.entrar(mvc, json, "GestorG " + s), TestesAuth.entrar(mvc, json, "GestorF " + s),
                TestesAuth.entrar(mvc, json, "UsuG " + s), TestesAuth.entrar(mvc, json, "UsuF " + s), geral, fin, fluxo, cliente, ug, uf);
    }

    private String nomes(JsonNode lista) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode e : lista) sb.append(e.get("nome").asText()).append('|');
        return sb.toString();
    }

    @Test
    void gestorNaoCriaSetorMasCriaEntregaNoSetorDeleEOutroSetorNaoVe() throws Exception {
        C c = cenario();
        enviar(c.gestorGeral(), "/api/setores", "{\"nome\":\"Novo\"}").andExpect(status().isForbidden());
        alterar(c.gestorGeral(), "/api/setores/" + c.geral(), "{\"nome\":\"Renomeado\"}").andExpect(status().isForbidden());
        mvc.perform(delete("/api/setores/" + c.fin()).header("Authorization", "Bearer " + c.gestorGeral())).andExpect(status().isForbidden());

        JsonNode nova = corpo(enviar(c.gestorGeral(), "/api/entregas", "{\"nome\":\"Laudo do Geral\"}").andExpect(status().isCreated()));
        assertThat(nova.get("setorDonoId").asLong()).isEqualTo(c.geral());
        long entrega = nova.get("id").asLong();
        // o gestor não escolhe outro setor: o pedido para o Financeiro cai no setor dele
        assertThat(corpo(enviar(c.gestorGeral(), "/api/entregas", "{\"nome\":\"Tentou o Fin\",\"setorId\":" + c.fin() + "}").andExpect(status().isCreated())).get("setorDonoId").asLong()).isEqualTo(c.geral());
        // administrador precisa dizer o setor
        enviar(c.admin(), "/api/entregas", "{\"nome\":\"Sem setor\"}").andExpect(status().isUnprocessableEntity());
        long doFin = corpo(enviar(c.admin(), "/api/entregas", "{\"nome\":\"Do Financeiro\",\"setorId\":" + c.fin() + "}").andExpect(status().isCreated())).get("id").asLong();

        // cada setor vê só as suas; o administrador vê todas
        assertThat(nomes(corpo(ler(c.gestorGeral(), "/api/entregas")))).contains("Laudo do Geral").doesNotContain("Do Financeiro");
        assertThat(nomes(corpo(ler(c.usuarioGeral(), "/api/entregas")))).contains("Laudo do Geral").doesNotContain("Do Financeiro");
        assertThat(nomes(corpo(ler(c.gestorFin(), "/api/entregas")))).contains("Do Financeiro").doesNotContain("Laudo do Geral");
        assertThat(nomes(corpo(ler(c.usuarioFin(), "/api/entregas")))).doesNotContain("Laudo do Geral");
        assertThat(nomes(corpo(ler(c.admin(), "/api/entregas")))).contains("Laudo do Geral").contains("Do Financeiro");
        for (JsonNode s : corpo(ler(c.gestorFin(), "/api/setores"))) assertThat(nomes(s.get("entregas"))).doesNotContain("Laudo do Geral");
        for (JsonNode s : corpo(ler(c.admin(), "/api/setores"))) {
            assertThat(nomes(s.get("entregas")).contains("Laudo do Geral")).isEqualTo(s.get("id").asLong() == c.geral());
        }

        // processo: o setor dono usa; o outro não, nem pelo administrador
        String corpoProc = "{\"clienteId\":" + c.cliente() + ",\"entregaId\":%d,\"setorId\":%d,\"responsavelId\":%d}";
        enviar(c.admin(), "/api/processos", corpoProc.formatted(entrega, c.geral(), c.usuarioGeralId())).andExpect(status().isCreated());
        enviar(c.admin(), "/api/processos", corpoProc.formatted(entrega, c.fin(), c.usuarioFinId())).andExpect(status().isUnprocessableEntity());
        enviar(c.admin(), "/api/processos", corpoProc.formatted(doFin, c.geral(), c.usuarioGeralId())).andExpect(status().isUnprocessableEntity());
        enviar(c.usuarioFin(), "/api/processos", "{\"clienteId\":" + c.cliente() + ",\"entregaId\":" + entrega + "}").andExpect(status().isUnprocessableEntity());
    }

    @Test
    void gestorSoMexeNasEntregasDoProprioSetor_eNomeEUnicoNaEmpresa() throws Exception {
        C c = cenario();
        long doGeral = corpo(enviar(c.gestorGeral(), "/api/entregas", "{\"nome\":\"Atendimento\"}").andExpect(status().isCreated())).get("id").asLong();
        alterar(c.gestorGeral(), "/api/entregas/" + doGeral, "{\"nome\":\"Atendimento 2\"}").andExpect(status().isOk());
        alterar(c.gestorFin(), "/api/entregas/" + doGeral, "{\"ativo\":false}").andExpect(status().isNotFound());                 // de outro setor: nem existe
        mvc.perform(delete("/api/entregas/" + doGeral).header("Authorization", "Bearer " + c.gestorFin())).andExpect(status().isNotFound());
        enviar(c.usuarioGeral(), "/api/entregas", "{\"nome\":\"X\"}").andExpect(status().isForbidden());
        alterar(c.usuarioGeral(), "/api/entregas/" + doGeral, "{\"ativo\":false}").andExpect(status().isForbidden());

        // o nome é único na empresa: nem no mesmo setor, nem em outro
        enviar(c.gestorFin(), "/api/entregas", "{\"nome\":\"Atendimento 2\"}").andExpect(status().isUnprocessableEntity());
        enviar(c.gestorGeral(), "/api/entregas", "{\"nome\":\"atendimento 2\"}").andExpect(status().isUnprocessableEntity());
        enviar(c.gestorFin(), "/api/entregas", "{\"nome\":\"Atendimento do Fin\"}").andExpect(status().isCreated());

        mvc.perform(delete("/api/entregas/" + doGeral).header("Authorization", "Bearer " + c.gestorGeral())).andExpect(status().isNoContent());
        assertThat(nomes(corpo(ler(c.gestorGeral(), "/api/entregas")))).doesNotContain("Atendimento 2");
        assertThat(nomes(corpo(ler(c.gestorFin(), "/api/entregas")))).contains("Atendimento do Fin");
    }

    @Test
    void apagarOSetorApagaAsEntregasDele() throws Exception {
        C c = cenario();
        enviar(c.gestorFin(), "/api/entregas", "{\"nome\":\"So do Financeiro\"}").andExpect(status().isCreated());
        assertThat(nomes(corpo(ler(c.admin(), "/api/entregas")))).contains("So do Financeiro");
        mvc.perform(delete("/api/setores/" + c.fin()).header("Authorization", "Bearer " + c.admin())).andExpect(status().isNoContent());
        assertThat(nomes(corpo(ler(c.admin(), "/api/entregas")))).doesNotContain("So do Financeiro");
    }

    private JsonNode fluxoDaEntrega(String t, long entregaId) throws Exception {
        long fluxoId = 0;
        for (JsonNode e : corpo(ler(t, "/api/entregas"))) if (e.get("id").asLong() == entregaId) fluxoId = e.get("fluxoId").asLong();
        for (JsonNode f : corpo(ler(t, "/api/fluxos"))) if (f.get("id").asLong() == fluxoId) return f;
        return null;
    }

    @Test
    void entregaNasceComFluxoProprioEOFluxoSegueOSetorDaEntrega() throws Exception {
        C c = cenario();
        String etapas = "[{\"nome\":\"Início\",\"inicial\":true},{\"nome\":\"Meio\"},{\"nome\":\"Fim\",\"prazoDias\":9,\"finalEtapa\":true}]";
        JsonNode e1 = corpo(enviar(c.gestorGeral(), "/api/entregas", "{\"nome\":\"Com etapas\",\"etapas\":" + etapas + "}").andExpect(status().isCreated()));
        JsonNode f1 = fluxoDaEntrega(c.gestorGeral(), e1.get("id").asLong());
        assertThat(f1.get("nome").asText()).isEqualTo("Com etapas");
        assertThat(f1.get("etapas")).hasSize(3);
        JsonNode e2 = corpo(enviar(c.gestorGeral(), "/api/entregas", "{\"nome\":\"Padrão\"}").andExpect(status().isCreated()));
        assertThat(fluxoDaEntrega(c.gestorGeral(), e2.get("id").asLong()).get("etapas")).hasSize(4);
        enviar(c.gestorGeral(), "/api/entregas", "{\"nome\":\"Atalho\",\"fluxoId\":" + c.fluxo() + "}").andExpect(status().isForbidden());
        enviar(c.gestorGeral(), "/api/entregas", "{\"nome\":\"Ruim\",\"etapas\":[{\"nome\":\"Só uma\",\"inicial\":true,\"finalEtapa\":true}]}").andExpect(status().isUnprocessableEntity());
        assertThat(nomes(corpo(ler(c.admin(), "/api/entregas")))).doesNotContain("Ruim");

        long fluxo1 = f1.get("id").asLong(), etapaMeio = f1.get("etapas").get(1).get("id").asLong();
        // o gestor do setor configura (inclusive criando etapas); o de outro setor nem vê; o usuário só consulta
        enviar(c.gestorGeral(), "/api/fluxos/" + fluxo1 + "/etapas/" + etapaMeio + "/requisitos", "{\"tipo\":\"CONFIRMACAO\",\"descricao\":\"Conferido\"}").andExpect(status().isCreated());
        enviar(c.gestorGeral(), "/api/fluxos/" + fluxo1 + "/etapas", "{\"nome\":\"Vistoria\"}").andExpect(status().isCreated());
        enviar(c.gestorFin(), "/api/fluxos/" + fluxo1 + "/etapas", "{\"nome\":\"Intruso\"}").andExpect(status().isForbidden());
        enviar(c.gestorFin(), "/api/fluxos/" + fluxo1 + "/etapas/" + etapaMeio + "/requisitos", "{\"tipo\":\"CONFIRMACAO\",\"descricao\":\"Intruso\"}").andExpect(status().isForbidden());
        enviar(c.usuarioGeral(), "/api/fluxos/" + fluxo1 + "/etapas", "{\"nome\":\"X\"}").andExpect(status().isForbidden());
        assertThat(fluxoDaEntrega(c.usuarioGeral(), e1.get("id").asLong())).isNotNull();
        ler(c.gestorFin(), "/api/fluxos/" + fluxo1).andExpect(status().isNotFound());
        assertThat(corpo(ler(c.gestorFin(), "/api/fluxos")).toString()).doesNotContain("Com etapas");
        ler(c.gestorFin(), "/api/fluxos/" + fluxo1 + "/automacoes").andExpect(status().isForbidden());
        enviar(c.gestorGeral(), "/api/fluxos", "{\"nome\":\"Solto\",\"etapas\":" + etapas + "}").andExpect(status().isForbidden());

        // apagar a entrega leva o fluxo junto
        mvc.perform(delete("/api/entregas/" + e1.get("id").asLong()).header("Authorization", "Bearer " + c.gestorGeral())).andExpect(status().isNoContent());
        ler(c.admin(), "/api/fluxos/" + fluxo1).andExpect(status().isNotFound());
    }
}
