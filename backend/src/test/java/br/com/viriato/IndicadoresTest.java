package br.com.viriato;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Painel de indicadores: atrasados por setor, tempo por etapa, carga por funcionário e abrangência por papel. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IndicadoresTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    record C(String admin, String gestorGeral, String usuarioGeral, String usuarioEng, long u1, long u2, long geral, long eng,
             long entregaGeral, long entregaEng, long cliente, long analise, long revisao, long concluido) {}

    private JsonNode corpo(ResultActions r) throws Exception { return json.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)); }
    private ResultActions enviar(String t, String url, String b) throws Exception {
        return mvc.perform(post(url).header("Authorization", "Bearer " + t).contentType(MediaType.APPLICATION_JSON).content(b));
    }
    private ResultActions ler(String t, String url) throws Exception { return mvc.perform(get(url).header("Authorization", "Bearer " + t)); }
    private JsonNode consultar(String t, String url) throws Exception { return corpo(ler(t, url).andExpect(status().isOk())); }
    private long usuario(String admin, String nome, String papel, long setor) throws Exception {
        return corpo(enviar(admin, "/api/usuarios", "{\"nome\":\"" + nome + "\",\"senha\":\"senha12345\",\"papel\":\"" + papel + "\",\"setorId\":" + setor + "}")
                .andExpect(status().isCreated())).get("id").asLong();
    }

    private C cenario() throws Exception {
        String s = UUID.randomUUID().toString().substring(0, 8);
        mvc.perform(post("/api/auth/registrar-empresa").contentType(MediaType.APPLICATION_JSON)
                .content("{\"empresa\":\"E\",\"nome\":\"Adm-" + s + "\",\"email\":\"a-" + s + "@t.com\",\"senha\":\"senha12345\"}")).andExpect(status().isCreated());
        String admin = TestesAuth.entrar(mvc, json, "a-" + s + "@t.com");
        JsonNode geralNode = consultar(admin, "/api/setores").get(0);
        long geral = geralNode.get("id").asLong(), entregaGeral = geralNode.get("entregas").get(0).get("id").asLong();
        long eng = corpo(enviar(admin, "/api/setores", "{\"nome\":\"Engenharia\",\"entregaIds\":[" + entregaGeral + "]}").andExpect(status().isCreated())).get("id").asLong();
        usuario(admin, "GesG-" + s, "GESTOR", geral);
        long u1 = usuario(admin, "Ana-" + s, "USUARIO", geral);
        long u2 = usuario(admin, "Edu-" + s, "USUARIO", eng);
        long cliente = corpo(enviar(admin, "/api/clientes", "{\"numero\":\"7\",\"nome\":\"Alfa\"}").andExpect(status().isCreated())).get("id").asLong();
        JsonNode et = consultar(admin, "/api/fluxos").get(0).get("etapas");
        return new C(admin, TestesAuth.entrar(mvc, json, "GesG-" + s), TestesAuth.entrar(mvc, json, "Ana-" + s), TestesAuth.entrar(mvc, json, "Edu-" + s),
                u1, u2, geral, eng, entregaGeral, entregaGeral, cliente, et.get(1).get("id").asLong(), et.get(2).get("id").asLong(), et.get(3).get("id").asLong());
    }

    private long processo(C c, long setor, long responsavel) throws Exception {
        return corpo(enviar(c.admin(), "/api/processos", "{\"clienteId\":" + c.cliente() + ",\"entregaId\":" + c.entregaGeral() + ",\"setorId\":" + setor
                + ",\"responsavelId\":" + responsavel + "}").andExpect(status().isCreated())).get("id").asLong();
    }

    private void avancar(C c, long p, long etapa) throws Exception {
        enviar(c.admin(), "/api/processos/" + p + "/avancar", "{\"etapaId\":" + etapa + "}").andExpect(status().isOk());
    }

    /** Leva o processo até Concluído e fixa as datas: entrada em Análise, Revisão e Concluído em dias após o início. */
    private void concluirComTempos(C c, long p, Instant inicio, int diasAnalise, int diasRevisao, int diasFim) throws Exception {
        avancar(c, p, c.analise());
        avancar(c, p, c.revisao());
        avancar(c, p, c.concluido());
        List<Long> ids = jdbc.queryForList("select id from processo_historico where processo_id = ? and acao in ('CRIADO','MOVIDO') order by id", Long.class, p);
        int[] dias = {0, diasAnalise, diasRevisao, diasFim};
        for (int i = 0; i < ids.size(); i++) {
            jdbc.update("update processo_historico set criado_em = ? where id = ?", Timestamp.from(inicio.plus(dias[i], ChronoUnit.DAYS)), ids.get(i));
        }
        jdbc.update("update processo set criado_em = ?, atualizado_em = ? where id = ?",
                Timestamp.from(inicio), Timestamp.from(inicio.plus(diasFim, ChronoUnit.DAYS)), p);
    }

    private void vencer(long p) {
        jdbc.update("update processo set prazo_etapa = ? where id = ?", java.sql.Date.valueOf(LocalDate.now().minusDays(3)), p);
    }

    @Test
    void contaAtrasadosPorSetor_resumoETempoMedioDeConclusao() throws Exception {
        C c = cenario();
        Instant inicio = Instant.now().minus(10, ChronoUnit.DAYS);
        long noPrazo = processo(c, c.geral(), c.u1());
        long atrasadoG = processo(c, c.geral(), c.u1());
        long atrasadoE = processo(c, c.eng(), c.u2());
        long concluido = processo(c, c.geral(), c.u1());
        long cancelado = processo(c, c.geral(), c.u1());
        vencer(atrasadoG);
        vencer(atrasadoE);
        concluirComTempos(c, concluido, inicio, 2, 5, 6);
        enviar(c.admin(), "/api/processos/" + cancelado + "/cancelar", "{\"motivo\":\"x\"}").andExpect(status().isOk());
        assertThat(noPrazo).isPositive();

        JsonNode p = consultar(c.admin(), "/api/indicadores");
        assertThat(p.get("todosOsSetores").asBoolean()).isTrue();
        assertThat(p.get("periodoDias").asInt()).isEqualTo(90);
        JsonNode r = p.get("resumo");
        assertThat(r.get("abertos").asInt()).isEqualTo(3);
        assertThat(r.get("atrasados").asInt()).isEqualTo(2);
        assertThat(r.get("concluidos").asInt()).isEqualTo(1);
        assertThat(r.get("cancelados").asInt()).isEqualTo(1);
        assertThat(r.get("tempoMedioConclusaoDias").asDouble()).isCloseTo(6.0, within(0.01));

        // atrasados por setor: Geral (2 abertos, 1 atrasado, 1 concluído, 1 cancelado) e Engenharia (1 aberto, 1 atrasado)
        JsonNode setores = p.get("setores");
        assertThat(setores).hasSize(2);
        assertThat(setores.get(0).get("setor").asText()).isEqualTo("Geral");
        assertThat(setores.get(0).get("abertos").asInt()).isEqualTo(2);
        assertThat(setores.get(0).get("atrasados").asInt()).isEqualTo(1);
        assertThat(setores.get(0).get("concluidos").asInt()).isEqualTo(1);
        assertThat(setores.get(0).get("cancelados").asInt()).isEqualTo(1);
        assertThat(setores.get(1).get("setor").asText()).isEqualTo("Engenharia");
        assertThat(setores.get(1).get("atrasados").asInt()).isEqualTo(1);

        // o administrador pode filtrar por setor
        JsonNode soEng = consultar(c.admin(), "/api/indicadores?setorId=" + c.eng());
        assertThat(soEng.get("escopo").asText()).isEqualTo("Setor Engenharia");
        assertThat(soEng.get("todosOsSetores").asBoolean()).isFalse();
        assertThat(soEng.get("resumo").get("abertos").asInt()).isEqualTo(1);
        assertThat(soEng.get("setores")).hasSize(1);
    }

    @Test
    void tempoMedioPorEtapa_comPrazoEPassagensForaDoPrazo() throws Exception {
        C c = cenario();
        Instant inicio = Instant.now().minus(10, ChronoUnit.DAYS);
        // Abertura (prazo 2 d): 2 d e 4 d; Análise (prazo 5 d): 3 d e 1 d; Revisão (prazo 3 d): 1 d e 1 d
        concluirComTempos(c, processo(c, c.geral(), c.u1()), inicio, 2, 5, 6);
        concluirComTempos(c, processo(c, c.geral(), c.u1()), inicio, 4, 5, 6);
        long aberto = processo(c, c.geral(), c.u1());   // etapa atual (Abertura) ainda em curso: não entra na média
        assertThat(aberto).isPositive();

        JsonNode etapas = consultar(c.admin(), "/api/indicadores").get("etapas");
        JsonNode abertura = null, analise = null, revisao = null;
        for (JsonNode e : etapas) {
            switch (e.get("etapa").asText()) {
                case "Abertura" -> abertura = e;
                case "Análise" -> analise = e;
                case "Revisão" -> revisao = e;
                default -> { }
            }
        }
        assertThat(abertura).isNotNull();
        assertThat(abertura.get("fluxo").asText()).isEqualTo("Fluxo padrão");
        assertThat(abertura.get("passagens").asInt()).isEqualTo(2);
        assertThat(abertura.get("mediaHoras").asDouble()).isCloseTo(72.0, within(0.1));     // (2 d + 4 d) / 2
        assertThat(abertura.get("prazoDias").asInt()).isEqualTo(2);
        assertThat(abertura.get("foraDoPrazo").asInt()).isEqualTo(1);                       // só a de 4 d passou do prazo
        assertThat(analise.get("mediaHoras").asDouble()).isCloseTo(48.0, within(0.1));      // (3 d + 1 d) / 2
        assertThat(analise.get("prazoDias").asInt()).isEqualTo(5);
        assertThat(analise.get("foraDoPrazo").asInt()).isZero();
        assertThat(revisao.get("passagens").asInt()).isEqualTo(2);
        assertThat(revisao.get("mediaHoras").asDouble()).isCloseTo(24.0, within(0.1));      // 1 d nos dois
        // ordenado do mais demorado para o menos
        for (int i = 1; i < etapas.size(); i++) {
            assertThat(etapas.get(i - 1).get("mediaHoras").asDouble()).isGreaterThanOrEqualTo(etapas.get(i).get("mediaHoras").asDouble());
        }
        // fora do período: nada
        assertThat(consultar(c.admin(), "/api/indicadores?dias=1").get("etapas")).isEmpty();
    }

    @Test
    void cargaPorFuncionario() throws Exception {
        C c = cenario();
        long a1 = processo(c, c.geral(), c.u1());
        long a2 = processo(c, c.geral(), c.u1());
        processo(c, c.eng(), c.u2());
        long fim = processo(c, c.geral(), c.u1());
        vencer(a1);
        concluirComTempos(c, fim, Instant.now().minus(3, ChronoUnit.DAYS), 1, 2, 3);
        assertThat(a2).isPositive();

        JsonNode f = consultar(c.admin(), "/api/indicadores").get("funcionarios");
        JsonNode ana = null, edu = null;
        for (JsonNode x : f) {
            if (x.get("nome").asText().startsWith("Ana-")) ana = x;
            if (x.get("nome").asText().startsWith("Edu-")) edu = x;
        }
        assertThat(ana.get("abertos").asInt()).isEqualTo(2);
        assertThat(ana.get("atrasados").asInt()).isEqualTo(1);
        assertThat(ana.get("concluidos").asInt()).isEqualTo(1);
        assertThat(ana.get("setor").asText()).isEqualTo("Geral");
        assertThat(edu.get("abertos").asInt()).isEqualTo(1);
        // quem tem mais processos em andamento vem primeiro
        assertThat(f.get(0).get("abertos").asInt()).isGreaterThanOrEqualTo(f.get(f.size() - 1).get("abertos").asInt());
    }

    @Test
    void gestorEUsuarioVeemSoOProprioSetor_eNaoBurlamPeloFiltro() throws Exception {
        C c = cenario();
        processo(c, c.geral(), c.u1());
        long e1 = processo(c, c.eng(), c.u2());
        vencer(e1);
        for (String quem : new String[]{c.gestorGeral(), c.usuarioGeral()}) {
            JsonNode p = consultar(quem, "/api/indicadores?setorId=" + c.eng());   // tenta olhar Engenharia
            assertThat(p.get("escopo").asText()).isEqualTo("Setor Geral");
            assertThat(p.get("todosOsSetores").asBoolean()).isFalse();
            assertThat(p.get("resumo").get("abertos").asInt()).isEqualTo(1);
            assertThat(p.get("resumo").get("atrasados").asInt()).isZero();
            assertThat(p.get("setores")).hasSize(1);
            for (JsonNode f : p.get("funcionarios")) assertThat(f.get("setor").asText()).isEqualTo("Geral");
        }
        // o funcionário de Engenharia vê o atraso do próprio setor
        assertThat(consultar(c.usuarioEng(), "/api/indicadores").get("resumo").get("atrasados").asInt()).isEqualTo(1);
        mvc.perform(get("/api/indicadores")).andExpect(status().isUnauthorized());
    }

    @Test
    void usuarioSemSetorRecebePainelVazioComAviso() throws Exception {
        C c = cenario();
        String nome = "Sem-" + UUID.randomUUID().toString().substring(0, 6);
        enviar(c.admin(), "/api/usuarios", "{\"nome\":\"" + nome + "\",\"senha\":\"senha12345\",\"papel\":\"USUARIO\"}").andExpect(status().isCreated());
        JsonNode p = consultar(TestesAuth.entrar(mvc, json, nome), "/api/indicadores");
        assertThat(p.get("escopo").asText()).contains("não tem setor");
        assertThat(p.get("resumo").get("abertos").asInt()).isZero();
        assertThat(p.get("setores")).isEmpty();
    }
}
