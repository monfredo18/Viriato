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

/** Gestor não cria setor; a entrega que ele cria vai para o setor dele e só esse setor a vê e usa. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EntregaExclusivaDoSetorTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    record C(String admin, String gestorGeral, String gestorFin, String usuarioGeral, String usuarioFin,
             long geral, long fin, long fluxo, long cliente, long usuarioGeralId, long usuarioFinId, long entregaGeral) {}

    private JsonNode corpo(ResultActions r) throws Exception { return json.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)); }
    private ResultActions enviar(String t, String url, String b) throws Exception {
        return mvc.perform(post(url).header("Authorization", "Bearer " + t).contentType(MediaType.APPLICATION_JSON).content(b));
    }
    private ResultActions alterar(String t, String url, String b) throws Exception {
        return mvc.perform(patch(url).header("Authorization", "Bearer " + t).contentType(MediaType.APPLICATION_JSON).content(b));
    }
    private ResultActions ler(String t, String url) throws Exception { return mvc.perform(get(url).header("Authorization", "Bearer " + t)); }
    private long criar(String admin, String nome, String papel, long setor) throws Exception {
        return corpo(enviar(admin, "/api/usuarios", "{\"nome\":\"" + nome + "\",\"senha\":\"senha12345\",\"papel\":\"" + papel + "\",\"setorId\":" + setor + "}").andExpect(status().isCreated())).get("id").asLong();
    }

    private C cenario() throws Exception {
        String s = UUID.randomUUID().toString().substring(0, 6);
        String admin = corpo(mvc.perform(post("/api/auth/registrar-empresa").contentType(MediaType.APPLICATION_JSON)
                .content("{\"empresa\":\"E\",\"nome\":\"Adm " + s + "\",\"email\":\"a-" + s + "@t.com\",\"senha\":\"senha12345\"}")).andExpect(status().isCreated())).get("token").asText();
        JsonNode setorGeral = corpo(ler(admin, "/api/setores")).get(0);
        long geral = setorGeral.get("id").asLong(), entregaGeral = setorGeral.get("entregas").get(0).get("id").asLong();
        long fin = corpo(enviar(admin, "/api/setores", "{\"nome\":\"Financeiro\",\"entregaIds\":[" + entregaGeral + "]}").andExpect(status().isCreated())).get("id").asLong();
        long fluxo = corpo(ler(admin, "/api/fluxos")).get(0).get("id").asLong();
        long cliente = corpo(enviar(admin, "/api/clientes", "{\"numero\":\"7\",\"nome\":\"Alfa\"}").andExpect(status().isCreated())).get("id").asLong();
        criar(admin, "GestorG " + s, "GESTOR", geral);
        criar(admin, "GestorF " + s, "GESTOR", fin);
        long ug = criar(admin, "UsuG " + s, "USUARIO", geral);
        long uf = criar(admin, "UsuF " + s, "USUARIO", fin);
        return new C(admin, TestesAuth.entrar(mvc, json, "GestorG " + s), TestesAuth.entrar(mvc, json, "GestorF " + s),
                TestesAuth.entrar(mvc, json, "UsuG " + s), TestesAuth.entrar(mvc, json, "UsuF " + s), geral, fin, fluxo, cliente, ug, uf, entregaGeral);
    }

    private String nomes(JsonNode lista) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode e : lista) sb.append(e.get("nome").asText()).append('|');
        return sb.toString();
    }

    @Test
    void gestorNaoCriaSetorMasCriaEntregaQueVaiParaOSetorDele() throws Exception {
        C c = cenario();
        enviar(c.gestorGeral(), "/api/setores", "{\"nome\":\"Novo\"}").andExpect(status().isForbidden());
        alterar(c.gestorGeral(), "/api/setores/" + c.geral(), "{\"nome\":\"Renomeado\"}").andExpect(status().isForbidden());
        mvc.perform(delete("/api/setores/" + c.fin()).header("Authorization", "Bearer " + c.gestorGeral())).andExpect(status().isForbidden());

        JsonNode nova = corpo(enviar(c.gestorGeral(), "/api/entregas", "{\"nome\":\"Laudo do Geral\"}").andExpect(status().isCreated()));
        assertThat(nova.get("setorDonoId").asLong()).isEqualTo(c.geral());
        long entrega = nova.get("id").asLong();

        // já está na lista do setor dele, e só dele
        for (JsonNode s : corpo(ler(c.admin(), "/api/setores"))) {
            boolean tem = nomes(s.get("entregas")).contains("Laudo do Geral");
            assertThat(tem).as("setor " + s.get("nome").asText()).isEqualTo(s.get("id").asLong() == c.geral());
        }
        // vê quem é do setor (gestor, usuário e o administrador); outro setor, não
        assertThat(nomes(corpo(ler(c.gestorGeral(), "/api/entregas")))).contains("Laudo do Geral");
        assertThat(nomes(corpo(ler(c.usuarioGeral(), "/api/entregas")))).contains("Laudo do Geral");
        assertThat(nomes(corpo(ler(c.admin(), "/api/entregas")))).contains("Laudo do Geral");
        assertThat(nomes(corpo(ler(c.gestorFin(), "/api/entregas")))).doesNotContain("Laudo do Geral");
        assertThat(nomes(corpo(ler(c.usuarioFin(), "/api/entregas")))).doesNotContain("Laudo do Geral");
        for (JsonNode s : corpo(ler(c.gestorFin(), "/api/setores"))) assertThat(nomes(s.get("entregas"))).doesNotContain("Laudo do Geral");

        // processo: o setor dele usa; o outro não
        String corpoProc = "{\"clienteId\":" + c.cliente() + ",\"entregaId\":" + entrega + ",\"setorId\":%d,\"responsavelId\":%d}";
        enviar(c.admin(), "/api/processos", corpoProc.formatted(c.geral(), c.usuarioGeralId())).andExpect(status().isCreated());
        enviar(c.admin(), "/api/processos", corpoProc.formatted(c.fin(), c.usuarioFinId())).andExpect(status().isUnprocessableEntity());
        enviar(c.usuarioFin(), "/api/processos", "{\"clienteId\":" + c.cliente() + ",\"entregaId\":" + entrega + "}").andExpect(status().isUnprocessableEntity());

        // o administrador não consegue colocá-la na lista de outro setor
        var recusa = corpo(alterar(c.admin(), "/api/setores/" + c.fin(), "{\"entregaIds\":[" + entrega + "]}").andExpect(status().isUnprocessableEntity()));
        assertThat(recusa.get("erro").asText()).contains("exclusiva");
        // e atualizar a lista do próprio setor não perde a exclusiva
        alterar(c.admin(), "/api/setores/" + c.geral(), "{\"entregaIds\":[" + c.entregaGeral() + "]}").andExpect(status().isOk());
        assertThat(nomes(corpo(ler(c.gestorGeral(), "/api/setores")).get(0).get("entregas")) + nomes(corpo(ler(c.gestorGeral(), "/api/setores")).get(1).get("entregas"))).contains("Laudo do Geral");
    }

    @Test
    void gestorSoMexeNasEntregasExclusivasDoPropriosetor() throws Exception {
        C c = cenario();
        long doGeral = corpo(enviar(c.gestorGeral(), "/api/entregas", "{\"nome\":\"Exclusiva Geral\"}").andExpect(status().isCreated())).get("id").asLong();
        long geralDoAdmin = corpo(enviar(c.admin(), "/api/entregas", "{\"nome\":\"Entrega Geral\",\"fluxoId\":" + c.fluxo() + "}").andExpect(status().isCreated())).get("id").asLong();

        alterar(c.gestorGeral(), "/api/entregas/" + doGeral, "{\"nome\":\"Exclusiva Geral 2\"}").andExpect(status().isOk());
        mvc.perform(delete("/api/entregas/" + geralDoAdmin).header("Authorization", "Bearer " + c.gestorGeral())).andExpect(status().isForbidden());      // geral: só o admin
        alterar(c.gestorGeral(), "/api/entregas/" + geralDoAdmin, "{\"ativo\":false}").andExpect(status().isForbidden());
        alterar(c.gestorFin(), "/api/entregas/" + doGeral, "{\"ativo\":false}").andExpect(status().isNotFound());                                   // de outro setor: nem existe
        mvc.perform(delete("/api/entregas/" + doGeral).header("Authorization", "Bearer " + c.gestorFin())).andExpect(status().isNotFound());
        enviar(c.usuarioGeral(), "/api/entregas", "{\"nome\":\"X\",\"fluxoId\":" + c.fluxo() + "}").andExpect(status().isForbidden());

        // o nome é único na empresa: nem repetir a exclusiva de outro setor, nem uma entrega geral
        enviar(c.gestorFin(), "/api/entregas", "{\"nome\":\"Exclusiva Geral 2\"}").andExpect(status().isUnprocessableEntity());
        enviar(c.gestorGeral(), "/api/entregas", "{\"nome\":\"exclusiva geral 2\"}").andExpect(status().isUnprocessableEntity());
        enviar(c.gestorFin(), "/api/entregas", "{\"nome\":\"Entrega Geral\"}").andExpect(status().isUnprocessableEntity());

        mvc.perform(delete("/api/entregas/" + doGeral).header("Authorization", "Bearer " + c.gestorGeral())).andExpect(status().isNoContent());
        assertThat(nomes(corpo(ler(c.admin(), "/api/entregas")))).doesNotContain("Exclusiva Geral 2|Entrega");
    }

    @Test
    void apagarOSetorApagaAsEntregasExclusivasDele() throws Exception {
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
    void entregaNasceComFluxoProprioEOFluxoSegueARegraDaEntrega() throws Exception {
        C c = cenario();
        // com etapas informadas: o fluxo é criado junto, com o nome da entrega
        String etapas = "[{\"nome\":\"Início\",\"prazoDias\":1,\"inicial\":true,\"finalEtapa\":false},{\"nome\":\"Meio\",\"prazoDias\":2,\"inicial\":false,\"finalEtapa\":false},{\"nome\":\"Fim\",\"inicial\":false,\"finalEtapa\":true}]";
        JsonNode e1 = corpo(enviar(c.gestorGeral(), "/api/entregas", "{\"nome\":\"Com etapas\",\"etapas\":" + etapas + "}").andExpect(status().isCreated()));
        JsonNode f1 = fluxoDaEntrega(c.gestorGeral(), e1.get("id").asLong());
        assertThat(f1.get("nome").asText()).isEqualTo("Com etapas");
        assertThat(f1.get("etapas")).hasSize(3);
        // sem etapas: etapas padrão
        JsonNode e2 = corpo(enviar(c.gestorGeral(), "/api/entregas", "{\"nome\":\"Padrão\"}").andExpect(status().isCreated()));
        assertThat(fluxoDaEntrega(c.gestorGeral(), e2.get("id").asLong()).get("etapas")).hasSize(4);
        // gestor não escolhe fluxo existente; etapas inválidas são recusadas
        enviar(c.gestorGeral(), "/api/entregas", "{\"nome\":\"Atalho\",\"fluxoId\":" + c.fluxo() + "}").andExpect(status().isForbidden());
        enviar(c.gestorGeral(), "/api/entregas", "{\"nome\":\"Ruim\",\"etapas\":[{\"nome\":\"Só uma\",\"inicial\":true,\"finalEtapa\":true}]}").andExpect(status().isUnprocessableEntity());
        assertThat(nomes(corpo(ler(c.admin(), "/api/entregas")))).doesNotContain("Ruim");

        long fluxo1 = f1.get("id").asLong();
        long etapaMeio = f1.get("etapas").get(1).get("id").asLong();
        // o gestor do setor dono configura o fluxo; o de outro setor nem o vê; o usuário só consulta
        enviar(c.gestorGeral(), "/api/fluxos/" + fluxo1 + "/etapas/" + etapaMeio + "/requisitos", "{\"tipo\":\"CONFIRMACAO\",\"descricao\":\"Conferido\"}").andExpect(status().isCreated());
        enviar(c.gestorFin(), "/api/fluxos/" + fluxo1 + "/etapas/" + etapaMeio + "/requisitos", "{\"tipo\":\"CONFIRMACAO\",\"descricao\":\"Intruso\"}").andExpect(status().isForbidden());
        enviar(c.usuarioGeral(), "/api/fluxos/" + fluxo1 + "/etapas/" + etapaMeio + "/requisitos", "{\"tipo\":\"CONFIRMACAO\",\"descricao\":\"X\"}").andExpect(status().isForbidden());
        assertThat(fluxoDaEntrega(c.usuarioGeral(), e1.get("id").asLong())).isNotNull();
        ler(c.gestorFin(), "/api/fluxos/" + fluxo1).andExpect(status().isNotFound());
        assertThat(corpo(ler(c.gestorFin(), "/api/fluxos")).toString()).doesNotContain("Com etapas");
        ler(c.gestorFin(), "/api/fluxos/" + fluxo1 + "/automacoes").andExpect(status().isForbidden());
        mvc.perform(patch("/api/fluxos/" + fluxo1).header("Authorization", "Bearer " + c.gestorFin()).contentType(MediaType.APPLICATION_JSON).content("{\"ativo\":false}")).andExpect(status().isForbidden());

        // fluxo de entrega geral (ou compartilhado): o gestor consulta mas não altera
        enviar(c.gestorGeral(), "/api/fluxos/" + c.fluxo() + "/etapas/" + corpo(ler(c.admin(), "/api/fluxos/" + c.fluxo())).get("etapas").get(1).get("id").asLong() + "/requisitos",
                "{\"tipo\":\"CONFIRMACAO\",\"descricao\":\"Não pode\"}").andExpect(status().isForbidden());
        enviar(c.gestorGeral(), "/api/fluxos", "{\"nome\":\"Solto\",\"etapas\":" + etapas + "}").andExpect(status().isForbidden());

        // apagar a entrega leva o fluxo junto (com requisito e tudo)
        mvc.perform(delete("/api/entregas/" + e1.get("id").asLong()).header("Authorization", "Bearer " + c.gestorGeral())).andExpect(status().isNoContent());
        ler(c.admin(), "/api/fluxos/" + fluxo1).andExpect(status().isNotFound());
    }
}
