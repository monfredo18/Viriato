package br.com.viriato;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.viriato.processo.ProcessoRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Relatórios em PDF: administrador (todos os setores), gestor (só o próprio setor) e usuário
 * (só os próprios processos), com filtros por responsável, empresa, setor, período e situação.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RelatorioTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ProcessoRepository processos;

    static final LocalDate HOJE = LocalDate.now(ZoneId.of("America/Sao_Paulo"));

    record Cenario(String admin, String gestorEng, String gestorSemSetor, String u1, String u2,
                   long adminId, long gestorEngId, long u1Id, long u2Id,
                   long setorEng, long setorFin, long empresaA, long empresaB) {}

    private JsonNode corpo(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private ResultActions enviar(String token, String url, String body) throws Exception {
        return mvc.perform(post(url).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private JsonNode consultar(String token, String url) throws Exception {
        return corpo(mvc.perform(get(url).header("Authorization", "Bearer " + token)).andExpect(status().isOk()));
    }

    private ResultActions resumo(String token, String query) throws Exception {
        return mvc.perform(get("/api/relatorios/processos/resumo" + query).header("Authorization", "Bearer " + token));
    }

    private int total(String token, String query) throws Exception {
        return corpo(resumo(token, query).andExpect(status().isOk())).get("total").asInt();
    }

    private String login(String email) throws Exception {
        return TestesAuth.entrar(mvc, json, email);
    }

    private long idDe(JsonNode lista, String nome) {
        for (JsonNode n : lista) if (n.get("nome").asText().equals(nome)) return n.get("id").asLong();
        throw new AssertionError("não achei " + nome);
    }

    private String textoDoPdf(byte[] pdf) throws Exception {
        PdfReader reader = new PdfReader(pdf);
        PdfTextExtractor ext = new PdfTextExtractor(reader);
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= reader.getNumberOfPages(); i++) sb.append(ext.getTextFromPage(i)).append('\n');
        reader.close();
        return sb.toString();
    }

    private byte[] pdf(String token, String query) throws Exception {
        MvcResult r = mvc.perform(get("/api/relatorios/processos/pdf" + query).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        assertThat(r.getResponse().getContentType()).isEqualTo("application/pdf");
        return r.getResponse().getContentAsByteArray();
    }

    private void concluir(String admin, long processoId) throws Exception {
        for (int i = 0; i < 10; i++) {
            JsonNode det = consultar(admin, "/api/processos/" + processoId);
            if (det.get("processo").get("status").asText().equals("CONCLUIDO")) return;
            enviar(admin, "/api/processos/" + processoId + "/avancar", "{\"etapaId\":" + det.get("proximasEtapas").get(0).get("id").asLong() + "}")
                    .andExpect(status().isOk());
        }
    }

    /**
     * Empresa com setores Engenharia e Financeiro e cinco processos:
     * P1 (A, Eng, u1, aberto) · P2 (B, Eng, u1, concluído) · P3 (A, Fin, u2, cancelado)
     * P4 (B, Fin, u2, aberto) · P5 (A, Eng, gestor de Eng, aberto e atrasado).
     */
    private Cenario cenario() throws Exception {
        String sfx = UUID.randomUUID().toString();
        var reg = corpo(mvc.perform(post("/api/auth/registrar-empresa").contentType(MediaType.APPLICATION_JSON)
                .content("{\"empresa\":\"Construtora Teste\",\"nome\":\"Adm\",\"email\":\"adm-" + sfx + "@t.com\",\"senha\":\"senha12345\"}"))
                .andExpect(status().isCreated()));
        String admin = reg.get("token").asText();
        long entrega = consultar(admin, "/api/setores").get(0).get("entregas").get(0).get("id").asLong();
        long eng = corpo(enviar(admin, "/api/setores", "{\"nome\":\"Engenharia\",\"entregaIds\":[" + entrega + "]}").andExpect(status().isCreated())).get("id").asLong();
        long fin = corpo(enviar(admin, "/api/setores", "{\"nome\":\"Financeiro\",\"entregaIds\":[" + entrega + "]}").andExpect(status().isCreated())).get("id").asLong();
        String[][] us = {{"Gestor Eng", "GESTOR", "ge", "" + eng}, {"Gestor Sem Setor", "GESTOR", "gs", ""},
                         {"Ursula Um", "USUARIO", "u1", "" + eng}, {"Ubiratan Dois", "USUARIO", "u2", "" + fin}};
        for (String[] u : us) {
            enviar(admin, "/api/usuarios", "{\"nome\":\"" + u[0] + "\",\"email\":\"" + u[2] + "-" + sfx + "@t.com\",\"senha\":\"senha12345\",\"papel\":\""
                    + u[1] + "\"" + (u[3].isEmpty() ? "" : ",\"setorId\":" + u[3]) + "}").andExpect(status().isCreated());
        }
        JsonNode equipe = consultar(admin, "/api/usuarios");
        long a = corpo(enviar(admin, "/api/clientes", "{\"numero\":\"1042\",\"nome\":\"Alfa Engenharia\"}").andExpect(status().isCreated())).get("id").asLong();
        long b = corpo(enviar(admin, "/api/clientes", "{\"numero\":\"2077\",\"nome\":\"Beta Construções\"}").andExpect(status().isCreated())).get("id").asLong();
        long adminId = reg.get("usuario").get("id").asLong(), ge = idDe(equipe, "Gestor Eng"), u1 = idDe(equipe, "Ursula Um"), u2 = idDe(equipe, "Ubiratan Dois");

        java.util.function.BiFunction<long[], String, Long> proc = (ids, ignorado) -> {
            try {
                return corpo(enviar(admin, "/api/processos", "{\"clienteId\":" + ids[0] + ",\"entregaId\":" + entrega + ",\"setorId\":" + ids[1]
                        + ",\"responsavelId\":" + ids[2] + "}").andExpect(status().isCreated())).get("id").asLong();
            } catch (Exception e) { throw new RuntimeException(e); }
        };
        proc.apply(new long[]{a, eng, u1}, "P1");
        long p2 = proc.apply(new long[]{b, eng, u1}, "P2");
        long p3 = proc.apply(new long[]{a, fin, u2}, "P3");
        proc.apply(new long[]{b, fin, u2}, "P4");
        long p5 = proc.apply(new long[]{a, eng, ge}, "P5");
        concluir(admin, p2);
        enviar(admin, "/api/processos/" + p3 + "/cancelar", "{\"motivo\":\"x\"}").andExpect(status().isOk());
        var atrasado = processos.findById(p5).orElseThrow();
        atrasado.setPrazoEtapa(HOJE.minusDays(3));
        processos.save(atrasado);

        return new Cenario(admin, login("ge-" + sfx + "@t.com"), login("gs-" + sfx + "@t.com"), login("u1-" + sfx + "@t.com"), login("u2-" + sfx + "@t.com"),
                adminId, ge, u1, u2, eng, fin, a, b);
    }

    // ------------------------------------------------------------------ administrador

    @Test
    void administradorVeTudoEFiltraPorSetorEmpresaUsuarioESituacao() throws Exception {
        Cenario c = cenario();
        JsonNode r = corpo(resumo(c.admin(), "").andExpect(status().isOk()));
        assertThat(r.get("total").asInt()).isEqualTo(5);
        assertThat(r.get("emAndamento").asInt()).isEqualTo(3);
        assertThat(r.get("concluidos").asInt()).isEqualTo(1);
        assertThat(r.get("cancelados").asInt()).isEqualTo(1);
        assertThat(r.get("atrasados").asInt()).isEqualTo(1);

        assertThat(total(c.admin(), "?setorId=" + c.setorEng())).isEqualTo(3);
        assertThat(total(c.admin(), "?setorId=" + c.setorFin())).isEqualTo(2);
        assertThat(total(c.admin(), "?clienteId=" + c.empresaA())).isEqualTo(3);
        assertThat(total(c.admin(), "?usuarioId=" + c.u1Id())).isEqualTo(2);
        assertThat(total(c.admin(), "?situacoes=CONCLUIDO")).isEqualTo(1);
        assertThat(total(c.admin(), "?situacoes=CANCELADO")).isEqualTo(1);
        assertThat(total(c.admin(), "?situacoes=ABERTO")).isEqualTo(3);
        assertThat(total(c.admin(), "?situacoes=CONCLUIDO,CANCELADO")).isEqualTo(2);
        assertThat(total(c.admin(), "?setorId=" + c.setorEng() + "&clienteId=" + c.empresaA() + "&situacoes=ABERTO")).isEqualTo(2);   // filtros combinam
    }

    @Test
    void filtroDePeriodoPorAberturaEPorEncerramento() throws Exception {
        Cenario c = cenario();
        String hoje = HOJE.toString(), ontem = HOJE.minusDays(1).toString(), amanha = HOJE.plusDays(1).toString();
        assertThat(total(c.admin(), "?de=" + hoje + "&ate=" + hoje)).isEqualTo(5);                 // tudo foi aberto hoje
        assertThat(total(c.admin(), "?de=" + ontem + "&ate=" + ontem)).isEqualTo(0);
        assertThat(total(c.admin(), "?de=" + amanha)).isEqualTo(0);
        assertThat(total(c.admin(), "?ate=" + ontem)).isEqualTo(0);
        assertThat(total(c.admin(), "?de=" + ontem + "&ate=" + amanha)).isEqualTo(5);
        // por encerramento só entram os processos concluídos ou cancelados
        assertThat(total(c.admin(), "?referencia=ENCERRAMENTO&de=" + hoje + "&ate=" + hoje)).isEqualTo(2);
        assertThat(total(c.admin(), "?referencia=ENCERRAMENTO&de=" + ontem + "&ate=" + ontem)).isEqualTo(0);
        // data inicial depois da final é recusada
        resumo(c.admin(), "?de=" + amanha + "&ate=" + ontem).andExpect(status().isUnprocessableEntity());
    }

    // ------------------------------------------------------------------ gestor

    @Test
    void gestorSoGeraRelatorioDoProprioSetor() throws Exception {
        Cenario c = cenario();
        assertThat(total(c.gestorEng(), "")).isEqualTo(3);                                         // só Engenharia
        assertThat(total(c.gestorEng(), "?setorId=" + c.setorEng())).isEqualTo(3);                 // o próprio setor é permitido
        resumo(c.gestorEng(), "?setorId=" + c.setorFin()).andExpect(status().isForbidden());       // outro setor, não
        assertThat(total(c.gestorEng(), "?usuarioId=" + c.u2Id())).isEqualTo(0);                   // usuário de outro setor não aparece
        assertThat(total(c.gestorEng(), "?usuarioId=" + c.u1Id())).isEqualTo(2);
        assertThat(total(c.gestorEng(), "?situacoes=CANCELADO")).isEqualTo(0);                     // o cancelado é do Financeiro
        assertThat(total(c.gestorEng(), "?clienteId=" + c.empresaB())).isEqualTo(1);
    }

    @Test
    void gestorSemSetorNaoGeraRelatorio() throws Exception {
        Cenario c = cenario();
        var erro = corpo(resumo(c.gestorSemSetor(), "").andExpect(status().isUnprocessableEntity()));
        assertThat(erro.get("erro").asText()).contains("setor");
        mvc.perform(get("/api/relatorios/processos/pdf").header("Authorization", "Bearer " + c.gestorSemSetor()))
                .andExpect(status().isUnprocessableEntity());
    }

    // ------------------------------------------------------------------ usuário

    @Test
    void usuarioSoGeraRelatorioDosProprioProcessos() throws Exception {
        Cenario c = cenario();
        assertThat(total(c.u1(), "")).isEqualTo(2);
        assertThat(total(c.u2(), "")).isEqualTo(2);
        assertThat(total(c.u1(), "?usuarioId=" + c.u1Id())).isEqualTo(2);                          // a si mesmo é permitido
        resumo(c.u1(), "?usuarioId=" + c.u2Id()).andExpect(status().isForbidden());                // de outro, não
        assertThat(total(c.u1(), "?situacoes=CONCLUIDO")).isEqualTo(1);
        assertThat(total(c.u1(), "?setorId=" + c.setorFin())).isEqualTo(0);                        // os dele são de Engenharia
    }

    @Test
    void escopoDizOQueCadaPapelPodeGerar() throws Exception {
        Cenario c = cenario();
        JsonNode adm = consultar(c.admin(), "/api/relatorios/escopo");
        assertThat(adm.get("abrangencia").asText()).isEqualTo("TODOS_SETORES");
        JsonNode ges = consultar(c.gestorEng(), "/api/relatorios/escopo");
        assertThat(ges.get("abrangencia").asText()).isEqualTo("SEU_SETOR");
        assertThat(ges.get("setorId").asLong()).isEqualTo(c.setorEng());
        assertThat(ges.get("setorNome").asText()).isEqualTo("Engenharia");
        JsonNode usu = consultar(c.u1(), "/api/relatorios/escopo");
        assertThat(usu.get("abrangencia").asText()).isEqualTo("PROPRIOS");
        assertThat(usu.get("usuarioId").asLong()).isEqualTo(c.u1Id());
        mvc.perform(get("/api/relatorios/escopo").header("Authorization", "Bearer " + c.gestorSemSetor())).andExpect(status().isOk());   // consulta liberada; o erro vem ao gerar
    }

    // ------------------------------------------------------------------ PDF

    @Test
    void pdfTraConteudoFiltradoPorPapel() throws Exception {
        Cenario c = cenario();
        byte[] doAdmin = pdf(c.admin(), "");
        assertThat(new String(doAdmin, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
        String t = textoDoPdf(doAdmin);
        assertThat(t).contains("Relatório de processos").contains("Construtora Teste")
                .contains("Alfa Engenharia").contains("Beta Construções").contains("1042").contains("2077")
                .contains("Engenharia").contains("Financeiro").contains("Todos os setores");

        // gestor de Engenharia: nenhum processo do Financeiro no documento
        String doGestor = textoDoPdf(pdf(c.gestorEng(), ""));
        assertThat(doGestor).contains("Engenharia").doesNotContain("Financeiro").doesNotContain("Ubiratan");

        // usuário: só os processos dele
        String doUsuario = textoDoPdf(pdf(c.u1(), ""));
        assertThat(doUsuario).contains("Ursula Um").doesNotContain("Financeiro").doesNotContain("Ubiratan").doesNotContain("Gestor Eng");

        // filtros aparecem descritos no documento
        String filtrado = textoDoPdf(pdf(c.admin(), "?setorId=" + c.setorFin() + "&situacoes=CANCELADO"));
        assertThat(filtrado).contains("Financeiro").contains("Cancelados").doesNotContain("Gestor Eng");

        // sem resultados: o documento diz isso
        assertThat(textoDoPdf(pdf(c.admin(), "?de=" + HOJE.minusDays(5) + "&ate=" + HOJE.minusDays(4)))).contains("Nenhum processo");
    }

    @Test
    void pdfVemComoAnexoComNomeDeArquivo() throws Exception {
        Cenario c = cenario();
        MvcResult r = mvc.perform(get("/api/relatorios/processos/pdf").header("Authorization", "Bearer " + c.admin()))
                .andExpect(status().isOk()).andReturn();
        assertThat(r.getResponse().getHeader("Content-Disposition")).contains("attachment").contains("relatorio-processos-").contains(".pdf");
    }

    @Test
    void parametrosInvalidosDao400ENaoErroInterno() throws Exception {
        Cenario c = cenario();
        resumo(c.admin(), "?situacoes=XYZ").andExpect(status().isBadRequest());
        resumo(c.admin(), "?de=ontem").andExpect(status().isBadRequest());
        resumo(c.admin(), "?referencia=OUTRA").andExpect(status().isBadRequest());
        resumo(c.admin(), "?setorId=abc").andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ segurança

    @Test
    void relatorioNaoMisturaEmpresasDoSistemaENaoAceitaVisitante() throws Exception {
        Cenario a = cenario();
        Cenario b = cenario();
        assertThat(total(a.admin(), "")).isEqualTo(5);                                             // só os da própria empresa
        assertThat(total(b.admin(), "")).isEqualTo(5);
        assertThat(total(a.admin(), "?clienteId=" + b.empresaA())).isEqualTo(0);                   // empresa de outra: nada
        assertThat(textoDoPdf(pdf(a.admin(), "?clienteId=" + b.empresaA()))).contains("Nenhum processo");
        mvc.perform(get("/api/relatorios/processos/resumo")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/relatorios/processos/pdf")).andExpect(status().isUnauthorized());
    }
}
