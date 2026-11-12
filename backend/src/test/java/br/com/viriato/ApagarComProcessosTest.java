package br.com.viriato;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.viriato.comum.Datas;
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

/** Apagar funcionário com processos: herdeiros e novo prazo para os em andamento; encerrados ficam restritos ao gestor. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApagarComProcessosTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    record C(String admin, String gestorEng, String gestorFin, String u2, String uFin,
             long idU1, long idU2, long idU3, long idFin, long eng, long entrega, long empresa) {}

    private JsonNode corpo(ResultActions r) throws Exception { return json.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)); }
    private ResultActions enviar(String t, String url, String b) throws Exception {
        return mvc.perform(post(url).header("Authorization", "Bearer " + t).contentType(MediaType.APPLICATION_JSON).content(b));
    }
    private ResultActions ler(String t, String url) throws Exception { return mvc.perform(get(url).header("Authorization", "Bearer " + t)); }
    private JsonNode consultar(String t, String url) throws Exception { return corpo(ler(t, url).andExpect(status().isOk())); }
    private String login(String e) throws Exception {
        return TestesAuth.entrar(mvc, json, e);
    }
    private long usuario(String admin, String nome, String email, String papel, long setor) throws Exception {
        return corpo(enviar(admin, "/api/usuarios", "{\"nome\":\"" + nome + "\",\"email\":\"" + email + "\",\"senha\":\"senha12345\",\"papel\":\"" + papel
                + "\",\"setorId\":" + setor + "}").andExpect(status().isCreated())).get("id").asLong();
    }

    /** Engenharia: gestor, Ursula (u1, a que será apagada), u2 e u3. Financeiro: gestor e um funcionário. */
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
        long u2 = usuario(admin, "Ubiratan Eng", "u2-" + s + "@t.com", "USUARIO", eng);
        long u3 = usuario(admin, "Ubirajara Eng", "u3-" + s + "@t.com", "USUARIO", eng);
        long uf = usuario(admin, "Ana Fin", "uf-" + s + "@t.com", "USUARIO", fin);
        long emp = corpo(enviar(admin, "/api/clientes", "{\"numero\":\"1042\",\"nome\":\"Alfa Engenharia\"}").andExpect(status().isCreated())).get("id").asLong();
        return new C(admin, login("ge-" + s + "@t.com"), login("gf-" + s + "@t.com"), login("u2-" + s + "@t.com"), login("uf-" + s + "@t.com"),
                u1, u2, u3, uf, eng, entrega, emp);
    }

    private long processo(C c, long responsavel) throws Exception {
        return corpo(enviar(c.admin(), "/api/processos", "{\"clienteId\":" + c.empresa() + ",\"entregaId\":" + c.entrega() + ",\"setorId\":" + c.eng()
                + ",\"responsavelId\":" + responsavel + "}").andExpect(status().isCreated())).get("id").asLong();
    }

    private void concluir(C c, long id) throws Exception {
        for (int i = 0; i < 10; i++) {
            JsonNode det = consultar(c.admin(), "/api/processos/" + id);
            if (det.get("processo").get("status").asText().equals("CONCLUIDO")) return;
            enviar(c.admin(), "/api/processos/" + id + "/avancar", "{\"etapaId\":" + det.get("proximasEtapas").get(0).get("id").asLong() + "}").andExpect(status().isOk());
        }
        throw new AssertionError("não concluiu");
    }

    private String herdeiros(String prazo, long... ids) {
        StringBuilder b = new StringBuilder("{\"herdeiroIds\":[");
        for (int i = 0; i < ids.length; i++) b.append(i > 0 ? "," : "").append(ids[i]);
        return b.append("],\"prazo\":").append(prazo == null ? "null" : "\"" + prazo + "\"").append("}").toString();
    }

    @Test
    void avisoMostraQuantosProcessosEQuemPodeApagar() throws Exception {
        C c = cenario();
        long p1 = processo(c, c.idU1()); processo(c, c.idU1());
        long p3 = processo(c, c.idU1()); concluir(c, p3);
        long p4 = processo(c, c.idU1());
        enviar(c.admin(), "/api/processos/" + p4 + "/cancelar", "{\"motivo\":\"desistiu\"}").andExpect(status().isOk());

        JsonNode pend = consultar(c.gestorEng(), "/api/usuarios/" + c.idU1() + "/pendencias");
        assertThat(pend.get("nome").asText()).isEqualTo("Ursula Eng");
        assertThat(pend.get("abertos").asInt()).isEqualTo(2);
        assertThat(pend.get("concluidos").asInt()).isEqualTo(1);
        assertThat(pend.get("cancelados").asInt()).isEqualTo(1);
        assertThat(pend.get("atrasados").asInt()).isZero();
        consultar(c.admin(), "/api/usuarios/" + c.idU1() + "/pendencias");

        // funcionário não tem acesso; gestor de outro setor também não
        ler(c.u2(), "/api/usuarios/" + c.idU1() + "/pendencias").andExpect(status().isForbidden());
        ler(c.gestorFin(), "/api/usuarios/" + c.idU1() + "/pendencias").andExpect(status().isForbidden());
        enviar(c.u2(), "/api/usuarios/" + c.idU1() + "/apagar", herdeiros("2099-01-01", c.idU2())).andExpect(status().isForbidden());
        enviar(c.gestorFin(), "/api/usuarios/" + c.idU1() + "/apagar", herdeiros("2099-01-01", c.idFin())).andExpect(status().isForbidden());
        mvc.perform(delete("/api/usuarios/" + c.idU1()).header("Authorization", "Bearer " + c.u2())).andExpect(status().isForbidden());

        // o apagar simples continua recusando quem tem processo em andamento
        mvc.perform(delete("/api/usuarios/" + c.idU1()).header("Authorization", "Bearer " + c.gestorEng())).andExpect(status().isUnprocessableEntity());
        assertThat(p1).isPositive();
    }

    @Test
    void exigeHerdeirosEPrazoValidos() throws Exception {
        C c = cenario();
        processo(c, c.idU1());
        String amanha = Datas.hoje().plusDays(1).toString();
        String url = "/api/usuarios/" + c.idU1() + "/apagar";
        enviar(c.gestorEng(), url, "{}").andExpect(status().isUnprocessableEntity());                                         // sem nada
        enviar(c.gestorEng(), url, herdeiros(amanha)).andExpect(status().isUnprocessableEntity());                            // sem herdeiros
        enviar(c.gestorEng(), url, herdeiros(null, c.idU2())).andExpect(status().isUnprocessableEntity());                    // sem prazo
        enviar(c.gestorEng(), url, herdeiros(Datas.hoje().minusDays(1).toString(), c.idU2())).andExpect(status().isUnprocessableEntity()); // prazo no passado
        enviar(c.gestorEng(), url, herdeiros(amanha, c.idU1())).andExpect(status().isUnprocessableEntity());                  // ele mesmo
        var resp = corpo(enviar(c.gestorEng(), url, herdeiros(amanha, c.idFin())).andExpect(status().isUnprocessableEntity())); // herdeiro de outro setor
        assertThat(resp.get("erro").asText()).contains("setor");
        // nada foi apagado nas tentativas recusadas
        assertThat(consultar(c.admin(), "/api/usuarios").toString()).contains("Ursula Eng");
        assertThat(consultar(c.admin(), "/api/processos").get("content").get(0).get("responsavelNome").asText()).isEqualTo("Ursula Eng");
    }

    @Test
    void processosEmAndamentoSaoDivididosComNovoPrazo_eEncerradosFicamRestritosAoGestor() throws Exception {
        C c = cenario();
        long a1 = processo(c, c.idU1()), a2 = processo(c, c.idU1()), a3 = processo(c, c.idU1());
        long fim = processo(c, c.idU1()); concluir(c, fim);
        long canc = processo(c, c.idU1());
        enviar(c.admin(), "/api/processos/" + canc + "/cancelar", "{\"motivo\":\"x\"}").andExpect(status().isOk());
        String prazo = Datas.hoje().plusDays(15).toString();

        enviar(c.gestorEng(), "/api/usuarios/" + c.idU1() + "/apagar", herdeiros(prazo, c.idU2(), c.idU3())).andExpect(status().isNoContent());
        assertThat(consultar(c.admin(), "/api/usuarios").toString()).doesNotContain("Ursula Eng");

        // em andamento: divididos entre os dois herdeiros, todos com o novo prazo
        int comU2 = 0, comU3 = 0;
        for (long id : new long[]{a1, a2, a3}) {
            JsonNode p = consultar(c.gestorEng(), "/api/processos/" + id).get("processo");
            assertThat(p.get("prazoEtapa").asText()).isEqualTo(prazo);
            assertThat(p.get("atrasado").asBoolean()).isFalse();
            String resp = p.get("responsavelNome").asText();
            if (resp.equals("Ubiratan Eng")) comU2++; else if (resp.equals("Ubirajara Eng")) comU3++;
            JsonNode hist = consultar(c.gestorEng(), "/api/processos/" + id).get("historico").get(0);
            assertThat(hist.get("comentario").asText()).contains("herdou de Ursula Eng").contains("Novo prazo");
        }
        assertThat(comU2 + comU3).isEqualTo(3);
        assertThat(comU2).isBetween(1, 2);

        // encerrados: continuam com o nome dele, marcado como removido
        for (long id : new long[]{fim, canc}) {
            JsonNode p = consultar(c.gestorEng(), "/api/processos/" + id).get("processo");
            assertThat(p.get("responsavelNome").asText()).isEqualTo("Ursula Eng (removido)");
            consultar(c.admin(), "/api/processos/" + id);
            // funcionário do setor, gestor de outro setor e funcionário de outro setor não acessam
            ler(c.u2(), "/api/processos/" + id).andExpect(status().isNotFound());
            ler(c.gestorFin(), "/api/processos/" + id).andExpect(status().isNotFound());
            ler(c.uFin(), "/api/processos/" + id).andExpect(status().isNotFound());
        }
        assertThat(consultar(c.gestorEng(), "/api/processos?tamanho=50").get("content")).hasSize(5);   // o gestor vê todos
        assertThat(consultar(c.admin(), "/api/processos?tamanho=50").get("content")).hasSize(5);
        var doFuncionario = consultar(c.u2(), "/api/processos?tamanho=50").get("content");              // o funcionário só vê os em andamento
        assertThat(doFuncionario).hasSize(3);
        for (JsonNode p : doFuncionario) assertThat(p.get("status").asText()).isEqualTo("ABERTO");
        assertThat(consultar(c.gestorFin(), "/api/processos?tamanho=50").get("content")).isEmpty();
    }

    @Test
    void quemSoTemProcessosEncerradosApagaSemHerdeiros() throws Exception {
        C c = cenario();
        long p = processo(c, c.idU1()); concluir(c, p);
        enviar(c.gestorEng(), "/api/usuarios/" + c.idU1() + "/apagar", "{}").andExpect(status().isNoContent());
        assertThat(consultar(c.gestorEng(), "/api/processos/" + p).get("processo").get("responsavelNome").asText()).isEqualTo("Ursula Eng (removido)");
        ler(c.u2(), "/api/processos/" + p).andExpect(status().isNotFound());
    }
}
