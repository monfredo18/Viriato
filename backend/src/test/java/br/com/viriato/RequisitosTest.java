package br.com.viriato;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.viriato.documento.ArmazenamentoArquivos;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Requisitos para sair de uma etapa: documento obrigatório e confirmação manual. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(RequisitosTest.StorageFake.class)
class RequisitosTest {

    @TestConfiguration
    static class StorageFake {
        @Bean @Primary
        ArmazenamentoArquivos storageEmMemoria() {
            Map<String, byte[]> mem = new ConcurrentHashMap<>();
            return new ArmazenamentoArquivos() {
                public void salvar(String c, InputStream in, long t, String ct) {
                    try { mem.put(c, in.readAllBytes()); } catch (Exception e) { throw new RuntimeException(e); }
                }
                public InputStream abrir(String c) { return new ByteArrayInputStream(mem.get(c)); }
                public void remover(String c) { mem.remove(c); }
            };
        }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    /** fluxo padrão: Abertura → Análise → Revisão → Concluído */
    record C(String admin, String gestor, String usuario, String outro, long usuarioId, long clienteId, long setorId, long entregaId,
             long fluxoId, long abertura, long analise, long revisao, long concluido) {}

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
        JsonNode geral = consultar(admin, "/api/setores").get(0);
        long setor = geral.get("id").asLong();
        usuario(admin, "Ges-" + s, "GESTOR", setor);
        long u = usuario(admin, "Usu-" + s, "USUARIO", setor);
        usuario(admin, "Outro-" + s, "USUARIO", setor);
        long cliente = corpo(enviar(admin, "/api/clientes", "{\"numero\":\"10\",\"nome\":\"Alfa\"}").andExpect(status().isCreated())).get("id").asLong();
        JsonNode fluxo = consultar(admin, "/api/fluxos").get(0);
        JsonNode et = fluxo.get("etapas");
        return new C(admin, TestesAuth.entrar(mvc, json, "Ges-" + s), TestesAuth.entrar(mvc, json, "Usu-" + s), TestesAuth.entrar(mvc, json, "Outro-" + s), u, cliente, setor,
                geral.get("entregas").get(0).get("id").asLong(), fluxo.get("id").asLong(),
                et.get(0).get("id").asLong(), et.get(1).get("id").asLong(), et.get(2).get("id").asLong(), et.get(3).get("id").asLong());
    }

    private long requisito(C c, long etapa, String tipo, String descricao) throws Exception {
        return corpo(enviar(c.gestor(), "/api/fluxos/" + c.fluxoId() + "/etapas/" + etapa + "/requisitos",
                "{\"tipo\":\"" + tipo + "\",\"descricao\":\"" + descricao + "\"}").andExpect(status().isCreated())).get("id").asLong();
    }

    private long processo(C c) throws Exception { return processo(c, c.entregaId()); }
    private long processo(C c, long entrega) throws Exception {
        return corpo(enviar(c.admin(), "/api/processos", "{\"clienteId\":" + c.clienteId() + ",\"entregaId\":" + entrega + ",\"setorId\":" + c.setorId()
                + ",\"responsavelId\":" + c.usuarioId() + "}").andExpect(status().isCreated())).get("id").asLong();
    }

    private ResultActions avancar(String t, long processo, long etapa) throws Exception {
        return enviar(t, "/api/processos/" + processo + "/avancar", "{\"etapaId\":" + etapa + "}");
    }

    private ResultActions anexar(String t, long processo, Long requisito) throws Exception {
        var arq = new MockMultipartFile("arquivo", "laudo.pdf", "application/pdf", "conteudo".getBytes());
        var req = multipart("/api/processos/" + processo + "/documentos").file(arq).header("Authorization", "Bearer " + t);
        if (requisito != null) req.param("requisitoId", String.valueOf(requisito));
        return mvc.perform(req);
    }

    private ResultActions confirmar(String t, long processo, long requisito, boolean cumprido) throws Exception {
        return mvc.perform(put("/api/processos/" + processo + "/requisitos/" + requisito).header("Authorization", "Bearer " + t)
                .contentType(MediaType.APPLICATION_JSON).content("{\"cumprido\":" + cumprido + "}"));
    }

    @Test
    void soAdminEGestorConfiguram_comRegrasDeValidacao() throws Exception {
        C c = cenario();
        String url = "/api/fluxos/" + c.fluxoId() + "/etapas/" + c.analise() + "/requisitos";
        enviar(c.usuario(), url, "{\"tipo\":\"DOCUMENTO\",\"descricao\":\"Laudo\"}").andExpect(status().isForbidden());
        long id = requisito(c, c.analise(), "DOCUMENTO", "Laudo técnico");
        enviar(c.admin(), url, "{\"tipo\":\"DOCUMENTO\",\"descricao\":\"  \"}").andExpect(status().isBadRequest());
        enviar(c.admin(), url, "{\"descricao\":\"Sem tipo\"}").andExpect(status().isBadRequest());
        enviar(c.admin(), url, "{\"tipo\":\"DOCUMENTO\",\"descricao\":\"" + "x".repeat(201) + "\"}").andExpect(status().isBadRequest());
        // etapa final não tem saída; etapa de outro fluxo/inexistente é 404
        enviar(c.admin(), "/api/fluxos/" + c.fluxoId() + "/etapas/" + c.concluido() + "/requisitos", "{\"tipo\":\"DOCUMENTO\",\"descricao\":\"x\"}")
                .andExpect(status().isUnprocessableEntity());
        enviar(c.admin(), "/api/fluxos/" + c.fluxoId() + "/etapas/999999/requisitos", "{\"tipo\":\"DOCUMENTO\",\"descricao\":\"x\"}").andExpect(status().isNotFound());
        // todos consultam pelo fluxo
        JsonNode reqs = consultar(c.usuario(), "/api/fluxos").get(0).get("etapas").get(1).get("requisitos");
        assertThat(reqs).hasSize(1);
        assertThat(reqs.get(0).get("descricao").asText()).isEqualTo("Laudo técnico");
        assertThat(reqs.get(0).get("tipo").asText()).isEqualTo("DOCUMENTO");
        // renomear e apagar
        mvc.perform(patch(url + "/" + id).header("Authorization", "Bearer " + c.gestor()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"descricao\":\"Laudo assinado\"}")).andExpect(status().isOk());
        mvc.perform(delete(url + "/" + id).header("Authorization", "Bearer " + c.usuario())).andExpect(status().isForbidden());
        mvc.perform(delete(url + "/" + id).header("Authorization", "Bearer " + c.gestor())).andExpect(status().isNoContent());
        assertThat(consultar(c.admin(), "/api/fluxos").get(0).get("etapas").get(1).get("requisitos")).isEmpty();
        // no máximo 10 por etapa
        for (int i = 0; i < 10; i++) requisito(c, c.analise(), "CONFIRMACAO", "Item " + i);
        enviar(c.admin(), url, "{\"tipo\":\"CONFIRMACAO\",\"descricao\":\"11º\"}").andExpect(status().isUnprocessableEntity());
    }

    @Test
    void naoAvancaSemCumprirOsRequisitosDaEtapa() throws Exception {
        C c = cenario();
        long laudo = requisito(c, c.analise(), "DOCUMENTO", "Laudo técnico");
        long dados = requisito(c, c.analise(), "CONFIRMACAO", "Dados conferidos");
        long p = processo(c);
        avancar(c.usuario(), p, c.analise()).andExpect(status().isOk());   // Abertura não tem requisitos

        // detalhe mostra os dois pendentes
        JsonNode det = consultar(c.usuario(), "/api/processos/" + p);
        assertThat(det.get("requisitos")).hasSize(2);
        assertThat(det.get("requisitos").get(0).get("cumprido").asBoolean()).isFalse();
        var msg = corpo(avancar(c.usuario(), p, c.revisao()).andExpect(status().isUnprocessableEntity())).get("erro").asText();
        assertThat(msg).contains("Análise").contains("Laudo técnico").contains("Dados conferidos");
        // vale também para gestor e administrador: ninguém pula requisito
        avancar(c.gestor(), p, c.revisao()).andExpect(status().isUnprocessableEntity());
        avancar(c.admin(), p, c.revisao()).andExpect(status().isUnprocessableEntity());

        // só a confirmação: falta o documento
        confirmar(c.usuario(), p, dados, true).andExpect(status().isNoContent());
        var msg2 = corpo(avancar(c.usuario(), p, c.revisao()).andExpect(status().isUnprocessableEntity())).get("erro").asText();
        assertThat(msg2).contains("Laudo técnico").doesNotContain("Dados conferidos");

        // anexa o documento ligado ao requisito
        var doc = corpo(anexar(c.usuario(), p, laudo).andExpect(status().isCreated()));
        assertThat(doc.get("requisitoId").asLong()).isEqualTo(laudo);
        assertThat(doc.get("requisito").asText()).isEqualTo("Laudo técnico");
        det = consultar(c.usuario(), "/api/processos/" + p);
        assertThat(det.get("requisitos").get(0).get("cumprido").asBoolean()).isTrue();
        assertThat(det.get("requisitos").get(0).get("documentoNome").asText()).isEqualTo("laudo.pdf");
        assertThat(det.get("requisitos").get(1).get("cumpridoPor").asText()).startsWith("Usu-");

        // desmarcar a confirmação volta a bloquear; remover o documento também
        confirmar(c.usuario(), p, dados, false).andExpect(status().isNoContent());
        avancar(c.usuario(), p, c.revisao()).andExpect(status().isUnprocessableEntity());
        confirmar(c.usuario(), p, dados, true).andExpect(status().isNoContent());
        avancar(c.usuario(), p, c.revisao()).andExpect(status().isOk());

        // o histórico registra as confirmações
        boolean achou = false;
        for (JsonNode h : consultar(c.admin(), "/api/processos/" + p).get("historico")) {
            if (h.get("acao").asText().equals("REQUISITO") && h.get("comentario").asText().equals("Confirmado: Dados conferidos")) achou = true;
        }
        assertThat(achou).isTrue();
    }

    @Test
    void removerODocumentoVoltaARequerer_eSoQuemPodeMexerCumpre() throws Exception {
        C c = cenario();
        long laudo = requisito(c, c.analise(), "DOCUMENTO", "Laudo técnico");
        long dados = requisito(c, c.analise(), "CONFIRMACAO", "Dados conferidos");
        long p = processo(c);
        avancar(c.usuario(), p, c.analise()).andExpect(status().isOk());

        // outro funcionário (não é o responsável) não confirma nem anexa
        confirmar(c.outro(), p, dados, true).andExpect(status().isUnprocessableEntity());
        anexar(c.outro(), p, laudo).andExpect(status().isUnprocessableEntity());
        // requisito de outra etapa, tipo trocado ou inexistente
        long deRevisao = requisito(c, c.revisao(), "CONFIRMACAO", "Só na revisão");
        confirmar(c.usuario(), p, deRevisao, true).andExpect(status().isUnprocessableEntity());
        confirmar(c.usuario(), p, laudo, true).andExpect(status().isUnprocessableEntity());       // laudo é documento
        anexar(c.usuario(), p, dados).andExpect(status().isUnprocessableEntity());                  // dados é confirmação
        confirmar(c.usuario(), p, 999999, true).andExpect(status().isNotFound());

        long docId = corpo(anexar(c.usuario(), p, laudo).andExpect(status().isCreated())).get("id").asLong();
        confirmar(c.usuario(), p, dados, true).andExpect(status().isNoContent());
        mvc.perform(delete("/api/documentos/" + docId).header("Authorization", "Bearer " + c.usuario())).andExpect(status().isNoContent());
        avancar(c.usuario(), p, c.revisao()).andExpect(status().isUnprocessableEntity());          // sem o laudo de novo
        // apagar o requisito libera
        mvc.perform(delete("/api/fluxos/" + c.fluxoId() + "/etapas/" + c.analise() + "/requisitos/" + laudo).header("Authorization", "Bearer " + c.gestor()))
                .andExpect(status().isNoContent());
        avancar(c.usuario(), p, c.revisao()).andExpect(status().isOk());
    }

    @Test
    void voltarNaoExigeRequisito_ereentradaExigeDeNovo() throws Exception {
        C c = cenario();
        // fluxo com volta: Abertura → Análise → Revisão → (Análise | Concluído)
        long fluxo = corpo(enviar(c.admin(), "/api/fluxos", """
                {"nome":"Com volta","etapas":[{"nome":"Abertura","inicial":true},{"nome":"Análise"},{"nome":"Revisão"},{"nome":"Concluído","finalEtapa":true}],
                 "transicoes":[{"de":0,"para":1},{"de":1,"para":2},{"de":2,"para":1},{"de":2,"para":3}]}""").andExpect(status().isCreated())).get("id").asLong();
        JsonNode et = consultar(c.admin(), "/api/fluxos/" + fluxo).get("etapas");
        long analise = et.get(1).get("id").asLong(), revisao = et.get(2).get("id").asLong(), fim = et.get(3).get("id").asLong();
        long entrega = corpo(enviar(c.admin(), "/api/entregas", "{\"nome\":\"Laudo\",\"fluxoId\":" + fluxo + "}").andExpect(status().isCreated())).get("id").asLong();
        mvc.perform(patch("/api/setores/" + c.setorId()).header("Authorization", "Bearer " + c.admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"entregaIds\":[" + c.entregaId() + "," + entrega + "]}")).andExpect(status().isOk());
        long laudo = corpo(enviar(c.admin(), "/api/fluxos/" + fluxo + "/etapas/" + analise + "/requisitos", "{\"tipo\":\"DOCUMENTO\",\"descricao\":\"Laudo\"}")
                .andExpect(status().isCreated())).get("id").asLong();
        long visto = corpo(enviar(c.admin(), "/api/fluxos/" + fluxo + "/etapas/" + revisao + "/requisitos", "{\"tipo\":\"CONFIRMACAO\",\"descricao\":\"Visto\"}")
                .andExpect(status().isCreated())).get("id").asLong();

        long p = processo(c, entrega);
        avancar(c.usuario(), p, analise).andExpect(status().isOk());
        anexar(c.usuario(), p, laudo).andExpect(status().isCreated());
        avancar(c.usuario(), p, revisao).andExpect(status().isOk());

        // voltar de Revisão (que tem "Visto" pendente) para Análise é permitido
        avancar(c.usuario(), p, analise).andExpect(status().isOk());
        // ao reentrar em Análise, o laudo da passagem anterior não vale mais
        assertThat(consultar(c.usuario(), "/api/processos/" + p).get("requisitos").get(0).get("cumprido").asBoolean()).isFalse();
        avancar(c.usuario(), p, revisao).andExpect(status().isUnprocessableEntity());
        anexar(c.usuario(), p, laudo).andExpect(status().isCreated());
        avancar(c.usuario(), p, revisao).andExpect(status().isOk());
        // e em Revisão o "Visto" é obrigatório para concluir
        avancar(c.usuario(), p, fim).andExpect(status().isUnprocessableEntity());
        confirmar(c.usuario(), p, visto, true).andExpect(status().isNoContent());
        avancar(c.usuario(), p, fim).andExpect(status().isOk());
        assertThat(consultar(c.admin(), "/api/processos/" + p).get("requisitos")).isEmpty();   // encerrado: sem checklist
    }

    @Test
    void apagarOFluxoLevaOsRequisitosJunto() throws Exception {
        C c = cenario();
        long fluxo = corpo(enviar(c.admin(), "/api/fluxos", "{\"nome\":\"Temporário\",\"etapas\":[{\"nome\":\"A\",\"inicial\":true},{\"nome\":\"B\",\"finalEtapa\":true}]}")
                .andExpect(status().isCreated())).get("id").asLong();
        long etapaA = consultar(c.admin(), "/api/fluxos/" + fluxo).get("etapas").get(0).get("id").asLong();
        enviar(c.admin(), "/api/fluxos/" + fluxo + "/etapas/" + etapaA + "/requisitos", "{\"tipo\":\"DOCUMENTO\",\"descricao\":\"Doc\"}").andExpect(status().isCreated());
        mvc.perform(delete("/api/fluxos/" + fluxo).header("Authorization", "Bearer " + c.admin())).andExpect(status().isNoContent());
        ler(c.admin(), "/api/fluxos/" + fluxo).andExpect(status().isNotFound());
    }
}
