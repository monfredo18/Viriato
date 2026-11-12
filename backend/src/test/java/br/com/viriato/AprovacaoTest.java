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

/** Etapas que só gestor (ou só administrador) pode receber: pedido, aprovação, recusa e cancelamento. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AprovacaoTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    record C(String sfx, String admin, String gestor, String usuario, String outro, String gestorOutroSetor, long usuarioId, long clienteId,
             long setorId, long entregaId, long fluxoId, long analise, long revisao, long concluido) {}

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
        long setor = geral.get("id").asLong(), entrega = geral.get("entregas").get(0).get("id").asLong();
        long outroSetor = corpo(enviar(admin, "/api/setores", "{\"nome\":\"Outro\",\"entregaIds\":[" + entrega + "]}").andExpect(status().isCreated())).get("id").asLong();
        usuario(admin, "Ges-" + s, "GESTOR", setor);
        long u = usuario(admin, "Usu-" + s, "USUARIO", setor);
        usuario(admin, "Outro-" + s, "USUARIO", setor);
        usuario(admin, "GesO-" + s, "GESTOR", outroSetor);
        long cliente = corpo(enviar(admin, "/api/clientes", "{\"numero\":\"10\",\"nome\":\"Alfa\"}").andExpect(status().isCreated())).get("id").asLong();
        JsonNode fluxo = consultar(admin, "/api/fluxos").get(0);
        JsonNode et = fluxo.get("etapas");
        C c = new C(s, admin, TestesAuth.entrar(mvc, json, "Ges-" + s), TestesAuth.entrar(mvc, json, "Usu-" + s), TestesAuth.entrar(mvc, json, "Outro-" + s),
                TestesAuth.entrar(mvc, json, "GesO-" + s), u, cliente, setor, entrega, fluxo.get("id").asLong(),
                et.get(1).get("id").asLong(), et.get(2).get("id").asLong(), et.get(3).get("id").asLong());
        return c;
    }

    private void exigir(C c, long etapa, String papel) throws Exception {
        mvc.perform(patch("/api/fluxos/" + c.fluxoId() + "/etapas/" + etapa).header("Authorization", "Bearer " + c.gestor())
                .contentType(MediaType.APPLICATION_JSON).content("{\"papelMinimo\":\"" + papel + "\"}")).andExpect(status().isOk());
    }

    /** processo com o usuário como responsável, já em Revisão */
    private long processoEmRevisao(C c) throws Exception {
        long p = corpo(enviar(c.admin(), "/api/processos", "{\"clienteId\":" + c.clienteId() + ",\"entregaId\":" + c.entregaId() + ",\"setorId\":" + c.setorId()
                + ",\"responsavelId\":" + c.usuarioId() + "}").andExpect(status().isCreated())).get("id").asLong();
        enviar(c.usuario(), "/api/processos/" + p + "/avancar", "{\"etapaId\":" + c.analise() + "}").andExpect(status().isOk());
        enviar(c.usuario(), "/api/processos/" + p + "/avancar", "{\"etapaId\":" + c.revisao() + "}").andExpect(status().isOk());
        return p;
    }

    private ResultActions avancar(String t, long p, long etapa) throws Exception { return enviar(t, "/api/processos/" + p + "/avancar", "{\"etapaId\":" + etapa + "}"); }
    private ResultActions solicitar(String t, long p, long etapa, String comentario) throws Exception {
        return enviar(t, "/api/processos/" + p + "/aprovacoes", "{\"etapaId\":" + etapa + (comentario == null ? "" : ",\"comentario\":\"" + comentario + "\"") + "}");
    }
    private JsonNode aprovacoes(String t, long p) throws Exception { return consultar(t, "/api/processos/" + p).get("aprovacoes"); }
    private ResultActions aprovar(String t, long p, long a) throws Exception { return enviar(t, "/api/processos/" + p + "/aprovacoes/" + a + "/aprovar", "{}"); }
    private ResultActions recusar(String t, long p, long a, String motivo) throws Exception {
        return enviar(t, "/api/processos/" + p + "/aprovacoes/" + a + "/recusar", "{\"motivo\":\"" + motivo + "\"}");
    }
    private String statusDoProcesso(String t, long p) throws Exception { return consultar(t, "/api/processos/" + p).get("processo").get("status").asText(); }

    @Test
    void soAdminEGestorConfiguram_eOPapelAparecePeloFluxo() throws Exception {
        C c = cenario();
        String url = "/api/fluxos/" + c.fluxoId() + "/etapas/" + c.concluido();
        mvc.perform(patch(url).header("Authorization", "Bearer " + c.usuario()).contentType(MediaType.APPLICATION_JSON).content("{\"papelMinimo\":\"GESTOR\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(patch(url).header("Authorization", "Bearer " + c.gestor()).contentType(MediaType.APPLICATION_JSON).content("{\"papelMinimo\":\"DIRETOR\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(patch(url).header("Authorization", "Bearer " + c.gestor()).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        mvc.perform(patch("/api/fluxos/" + c.fluxoId() + "/etapas/999999").header("Authorization", "Bearer " + c.gestor()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"papelMinimo\":\"GESTOR\"}")).andExpect(status().isNotFound());
        JsonNode antes = consultar(c.usuario(), "/api/fluxos").get(0).get("etapas");
        assertThat(antes.get(3).get("papelMinimo").asText()).isEqualTo("USUARIO");   // padrão: qualquer responsável
        exigir(c, c.concluido(), "GESTOR");
        assertThat(consultar(c.usuario(), "/api/fluxos").get(0).get("etapas").get(3).get("papelMinimo").asText()).isEqualTo("GESTOR");
    }

    @Test
    void usuarioNaoMoveDiretoParaEtapaDeGestor_masGestorEAdminMovem() throws Exception {
        C c = cenario();
        exigir(c, c.concluido(), "GESTOR");
        long p = processoEmRevisao(c);
        var erro = corpo(avancar(c.usuario(), p, c.concluido()).andExpect(status().isUnprocessableEntity())).get("erro").asText();
        assertThat(erro).contains("Concluído").contains("gestor ou administrador").contains("aprovação");
        assertThat(statusDoProcesso(c.admin(), p)).isEqualTo("ABERTO");
        avancar(c.gestor(), p, c.concluido()).andExpect(status().isOk());
        assertThat(statusDoProcesso(c.admin(), p)).isEqualTo("CONCLUIDO");
        // etapas sem restrição seguem livres para o usuário (Abertura → Análise já foi feito por ele em processoEmRevisao)
        long p2 = processoEmRevisao(c);
        avancar(c.admin(), p2, c.concluido()).andExpect(status().isOk());
    }

    @Test
    void pedidoEAprovacao_movemOProcessoEmNomeDoAprovador() throws Exception {
        C c = cenario();
        exigir(c, c.concluido(), "GESTOR");
        long p = processoEmRevisao(c);

        // quem pode mover direto não pede; quem não é responsável não pede
        solicitar(c.gestor(), p, c.concluido(), null).andExpect(status().isUnprocessableEntity());
        solicitar(c.outro(), p, c.concluido(), null).andExpect(status().isUnprocessableEntity());
        solicitar(c.usuario(), p, 999999, null).andExpect(status().isNotFound());
        solicitar(c.usuario(), p, c.concluido(), "Pronto para fechar").andExpect(status().isCreated());
        solicitar(c.usuario(), p, c.concluido(), null).andExpect(status().isUnprocessableEntity());   // já há um pendente

        JsonNode a = aprovacoes(c.usuario(), p).get(0);
        assertThat(a.get("status").asText()).isEqualTo("PENDENTE");
        assertThat(a.get("etapaDestino").asText()).isEqualTo("Concluído");
        assertThat(a.get("comentario").asText()).isEqualTo("Pronto para fechar");
        assertThat(a.get("solicitadoPor").asText()).startsWith("Usu-");
        assertThat(a.get("papelMinimo").asText()).isEqualTo("GESTOR");
        long aid = a.get("id").asLong();

        // o gestor do setor é avisado; o gestor de outro setor não vê o processo
        JsonNode avisos = consultar(c.gestor(), "/api/notificacoes");
        assertThat(avisos.get(0).get("titulo").asText()).startsWith("Aprovação solicitada para Concluído - Usu-");
        ler(c.gestorOutroSetor(), "/api/processos/" + p).andExpect(status().isNotFound());
        aprovar(c.gestorOutroSetor(), p, aid).andExpect(status().isNotFound());

        // usuário (nem o próprio solicitante) aprova ou recusa
        aprovar(c.usuario(), p, aid).andExpect(status().isForbidden());
        recusar(c.usuario(), p, aid, "não").andExpect(status().isForbidden());
        assertThat(statusDoProcesso(c.admin(), p)).isEqualTo("ABERTO");

        // o gestor aprova: o processo conclui, o histórico diz quem aprovou e quem pediu
        var dto = corpo(aprovar(c.gestor(), p, aid).andExpect(status().isOk()));
        assertThat(dto.get("status").asText()).isEqualTo("CONCLUIDO");
        JsonNode det = consultar(c.admin(), "/api/processos/" + p);
        assertThat(det.get("aprovacoes").get(0).get("status").asText()).isEqualTo("APROVADA");
        assertThat(det.get("aprovacoes").get(0).get("decididoPor").asText()).startsWith("Ges-");
        String mov = null;
        for (JsonNode h : det.get("historico")) if (h.get("acao").asText().equals("MOVIDO") && h.get("paraEtapa").asText().equals("Concluído")) mov = h.get("comentario").asText();
        assertThat(mov).contains("Aprovado por Ges-").contains("solicitado por Usu-");
        // decidir de novo é recusado
        aprovar(c.gestor(), p, aid).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void recusaExigeMotivo_eOUsuarioPodePedirDeNovo() throws Exception {
        C c = cenario();
        exigir(c, c.concluido(), "GESTOR");
        long p = processoEmRevisao(c);
        solicitar(c.usuario(), p, c.concluido(), null).andExpect(status().isCreated());
        long aid = aprovacoes(c.usuario(), p).get(0).get("id").asLong();

        mvc.perform(post("/api/processos/" + p + "/aprovacoes/" + aid + "/recusar").header("Authorization", "Bearer " + c.gestor())
                .contentType(MediaType.APPLICATION_JSON).content("{\"motivo\":\"  \"}")).andExpect(status().isBadRequest());
        recusar(c.gestor(), p, aid, "Falta anexar o parecer").andExpect(status().isNoContent());

        JsonNode a = aprovacoes(c.usuario(), p).get(0);
        assertThat(a.get("status").asText()).isEqualTo("RECUSADA");
        assertThat(a.get("motivo").asText()).isEqualTo("Falta anexar o parecer");
        assertThat(a.get("decididoPor").asText()).startsWith("Ges-");
        assertThat(statusDoProcesso(c.admin(), p)).isEqualTo("ABERTO");
        boolean noHistorico = false;
        for (JsonNode h : consultar(c.usuario(), "/api/processos/" + p).get("historico")) {
            if (h.get("acao").asText().equals("APROVACAO_RECUSADA") && h.get("comentario").asText().contains("Falta anexar o parecer")) noHistorico = true;
        }
        assertThat(noHistorico).isTrue();

        solicitar(c.usuario(), p, c.concluido(), "Anexei o parecer").andExpect(status().isCreated());   // novo pedido
        assertThat(aprovacoes(c.usuario(), p)).hasSize(2);
    }

    @Test
    void retirarPedido_eCancelamentoAutomaticoQuandoOProcessoAndaPorOutroCaminho() throws Exception {
        C c = cenario();
        exigir(c, c.concluido(), "GESTOR");
        long p = processoEmRevisao(c);
        solicitar(c.usuario(), p, c.concluido(), null).andExpect(status().isCreated());
        long aid = aprovacoes(c.usuario(), p).get(0).get("id").asLong();

        // outro funcionário não retira; o solicitante sim
        mvc.perform(delete("/api/processos/" + p + "/aprovacoes/" + aid).header("Authorization", "Bearer " + c.outro())).andExpect(status().isForbidden());
        mvc.perform(delete("/api/processos/" + p + "/aprovacoes/" + aid).header("Authorization", "Bearer " + c.usuario())).andExpect(status().isNoContent());
        assertThat(aprovacoes(c.usuario(), p).get(0).get("status").asText()).isEqualTo("CANCELADA");

        // pedido novo; o gestor conclui direto e o pedido é cancelado sozinho
        solicitar(c.usuario(), p, c.concluido(), null).andExpect(status().isCreated());
        avancar(c.gestor(), p, c.concluido()).andExpect(status().isOk());
        assertThat(aprovacoes(c.usuario(), p).get(0).get("status").asText()).isEqualTo("CANCELADA");

        // processo cancelado também encerra os pendentes
        long p2 = processoEmRevisao(c);
        solicitar(c.usuario(), p2, c.concluido(), null).andExpect(status().isCreated());
        enviar(c.gestor(), "/api/processos/" + p2 + "/cancelar", "{\"motivo\":\"x\"}").andExpect(status().isOk());
        assertThat(aprovacoes(c.admin(), p2).get(0).get("status").asText()).isEqualTo("CANCELADA");
        // gestor retira pedido de outra pessoa
        long p3 = processoEmRevisao(c);
        solicitar(c.usuario(), p3, c.concluido(), null).andExpect(status().isCreated());
        long a3 = aprovacoes(c.usuario(), p3).get(0).get("id").asLong();
        mvc.perform(delete("/api/processos/" + p3 + "/aprovacoes/" + a3).header("Authorization", "Bearer " + c.gestor())).andExpect(status().isNoContent());
    }

    @Test
    void etapaSoDeAdministrador_gestorPedeEAdminAprova() throws Exception {
        C c = cenario();
        exigir(c, c.concluido(), "ADMIN");
        long p = processoEmRevisao(c);
        var erro = corpo(avancar(c.gestor(), p, c.concluido()).andExpect(status().isUnprocessableEntity())).get("erro").asText();
        assertThat(erro).contains("exige administrador");
        solicitar(c.gestor(), p, c.concluido(), "Autorizo, falta o admin").andExpect(status().isCreated());
        long aid = aprovacoes(c.admin(), p).get(0).get("id").asLong();

        // quem recebe o aviso é o administrador (o gestor não consegue aprovar)
        JsonNode avisos = consultar(c.admin(), "/api/notificacoes");
        assertThat(avisos.get(0).get("titulo").asText()).startsWith("Aprovação solicitada para Concluído - Ges-");
        aprovar(c.gestor(), p, aid).andExpect(status().isForbidden());
        aprovar(c.admin(), p, aid).andExpect(status().isOk());
        assertThat(statusDoProcesso(c.admin(), p)).isEqualTo("CONCLUIDO");
    }

    @Test
    void requisitosDaEtapaDeOrigemValemParaPedirEParaAprovar() throws Exception {
        C c = cenario();
        exigir(c, c.concluido(), "GESTOR");
        long req = corpo(enviar(c.gestor(), "/api/fluxos/" + c.fluxoId() + "/etapas/" + c.revisao() + "/requisitos",
                "{\"tipo\":\"CONFIRMACAO\",\"descricao\":\"Visto final\"}").andExpect(status().isCreated())).get("id").asLong();
        long p = processoEmRevisao(c);
        var erro = corpo(solicitar(c.usuario(), p, c.concluido(), null).andExpect(status().isUnprocessableEntity())).get("erro").asText();
        assertThat(erro).contains("Visto final");
        mvc.perform(put("/api/processos/" + p + "/requisitos/" + req).header("Authorization", "Bearer " + c.usuario())
                .contentType(MediaType.APPLICATION_JSON).content("{\"cumprido\":true}")).andExpect(status().isNoContent());
        solicitar(c.usuario(), p, c.concluido(), null).andExpect(status().isCreated());
        long aid = aprovacoes(c.usuario(), p).get(0).get("id").asLong();
        // se o visto for retirado antes da aprovação, o gestor não consegue aprovar
        mvc.perform(put("/api/processos/" + p + "/requisitos/" + req).header("Authorization", "Bearer " + c.gestor())
                .contentType(MediaType.APPLICATION_JSON).content("{\"cumprido\":false}")).andExpect(status().isNoContent());
        aprovar(c.gestor(), p, aid).andExpect(status().isUnprocessableEntity());
        mvc.perform(put("/api/processos/" + p + "/requisitos/" + req).header("Authorization", "Bearer " + c.gestor())
                .contentType(MediaType.APPLICATION_JSON).content("{\"cumprido\":true}")).andExpect(status().isNoContent());
        aprovar(c.gestor(), p, aid).andExpect(status().isOk());
    }
}
