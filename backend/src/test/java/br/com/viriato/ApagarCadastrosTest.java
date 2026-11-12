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

/**
 * Apagar empresas, setores, entregas e funcionários: quem pode e o que o sistema protege.
 * Regra: só processo EM ANDAMENTO bloqueia; processo concluído ou cancelado continua existindo,
 * apenas sem o vínculo com o item apagado.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApagarCadastrosTest {

    private static final java.util.concurrent.atomic.AtomicInteger SEQ = new java.util.concurrent.atomic.AtomicInteger();

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    record Cenario(String admin, String gestor, String usuario, long adminId, long gestorId, long usuarioId,
                   String emailUsuario, long setorGeralId, long entregaPadraoId, long fluxoId) {}

    private JsonNode corpo(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private ResultActions enviar(String token, String url, String body) throws Exception {
        return mvc.perform(post(url).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions alterar(String token, String url, String body) throws Exception {
        return mvc.perform(patch(url).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions apagar(String token, String url) throws Exception {
        return mvc.perform(delete(url).header("Authorization", "Bearer " + token));
    }

    private JsonNode consultar(String token, String url) throws Exception {
        return corpo(mvc.perform(get(url).header("Authorization", "Bearer " + token)).andExpect(status().isOk()));
    }

    private String login(String email) throws Exception {
        return corpo(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"senha\":\"senha12345\"}")).andExpect(status().isOk())).get("token").asText();
    }

    private long idDe(JsonNode lista, String nome) {
        for (JsonNode n : lista) if (n.get("nome").asText().equals(nome)) return n.get("id").asLong();
        throw new AssertionError("não achei " + nome);
    }

    private Cenario cenario() throws Exception {
        String sfx = UUID.randomUUID().toString();
        var reg = corpo(mvc.perform(post("/api/auth/registrar-empresa").contentType(MediaType.APPLICATION_JSON)
                .content("{\"empresa\":\"Emp\",\"nome\":\"Adm\",\"email\":\"adm-" + sfx + "@t.com\",\"senha\":\"senha12345\"}"))
                .andExpect(status().isCreated()));
        String admin = reg.get("token").asText();
        long setorGeral = consultar(admin, "/api/setores").get(0).get("id").asLong();
        enviar(admin, "/api/usuarios", "{\"nome\":\"Gestor\",\"email\":\"ges-" + sfx + "@t.com\",\"senha\":\"senha12345\",\"papel\":\"GESTOR\",\"setorId\":" + setorGeral + "}").andExpect(status().isCreated());
        enviar(admin, "/api/usuarios", "{\"nome\":\"Func\",\"email\":\"usu-" + sfx + "@t.com\",\"senha\":\"senha12345\",\"papel\":\"USUARIO\",\"setorId\":" + setorGeral + "}").andExpect(status().isCreated());
        JsonNode equipe = consultar(admin, "/api/usuarios");
        JsonNode geral = consultar(admin, "/api/setores").get(0);
        return new Cenario(admin, login("ges-" + sfx + "@t.com"), login("usu-" + sfx + "@t.com"),
                reg.get("usuario").get("id").asLong(), idDe(equipe, "Gestor"), idDe(equipe, "Func"), "usu-" + sfx + "@t.com",
                geral.get("id").asLong(), geral.get("entregas").get(0).get("id").asLong(),
                consultar(admin, "/api/fluxos").get(0).get("id").asLong());
    }

    /** Abre um processo (empresa, setor Geral, entrega padrão) com o responsável indicado. */
    private long processo(Cenario c, long clienteId, long responsavelId) throws Exception {
        return corpo(enviar(c.admin(), "/api/processos", "{\"clienteId\":" + clienteId + ",\"entregaId\":" + c.entregaPadraoId()
                + ",\"setorId\":" + c.setorGeralId() + ",\"responsavelId\":" + responsavelId + "}").andExpect(status().isCreated())).get("id").asLong();
    }

    /** Leva o processo até a etapa final (concluído). */
    private void concluir(Cenario c, long processoId) throws Exception {
        for (int i = 0; i < 10; i++) {
            JsonNode det = consultar(c.admin(), "/api/processos/" + processoId);
            if (det.get("processo").get("status").asText().equals("CONCLUIDO")) return;
            long proxima = det.get("proximasEtapas").get(0).get("id").asLong();
            enviar(c.admin(), "/api/processos/" + processoId + "/avancar", "{\"etapaId\":" + proxima + "}").andExpect(status().isOk());
        }
        throw new AssertionError("processo não concluiu");
    }

    private JsonNode processo(Cenario c, long processoId) throws Exception {
        return consultar(c.admin(), "/api/processos/" + processoId).get("processo");
    }

    private long cliente(String token, String nome) throws Exception {
        String numero = String.valueOf(5000 + SEQ.incrementAndGet());
        return corpo(enviar(token, "/api/clientes", "{\"numero\":\"" + numero + "\",\"nome\":\"" + nome + "\"}").andExpect(status().isCreated())).get("id").asLong();
    }

    // ------------------------------------------------------------------ empresas

    @Test
    void empresaComProcessoEmAndamentoNaoApaga_concluidoOuCanceladoNaoImpede() throws Exception {
        Cenario c = cenario();
        long livre = cliente(c.gestor(), "Sem processos");
        apagar(c.usuario(), "/api/clientes/" + livre).andExpect(status().isForbidden());
        apagar(c.gestor(), "/api/clientes/" + livre).andExpect(status().isNoContent());

        long ocupada = cliente(c.admin(), "Com processos");
        long p = processo(c, ocupada, c.adminId());
        var resp = corpo(apagar(c.admin(), "/api/clientes/" + ocupada).andExpect(status().isUnprocessableEntity()));
        assertThat(resp.get("erro").asText()).contains("1 processo").contains("em andamento");
        assertThat(consultar(c.admin(), "/api/clientes")).hasSize(1);          // continua lá

        concluir(c, p);                                                         // concluído deixa de bloquear
        apagar(c.gestor(), "/api/clientes/" + ocupada).andExpect(status().isNoContent());
        assertThat(consultar(c.admin(), "/api/clientes")).isEmpty();
        JsonNode depois = processo(c, p);                                       // o processo continua, sem a empresa
        assertThat(depois.get("status").asText()).isEqualTo("CONCLUIDO");
        assertThat(depois.get("clienteNome").isNull()).isTrue();
        assertThat(depois.get("titulo").asText()).isEqualTo("Atendimento geral — Com processos");

        long cancelada = cliente(c.admin(), "Cancelada");                       // cancelado também não bloqueia
        long p2 = processo(c, cancelada, c.adminId());
        enviar(c.admin(), "/api/processos/" + p2 + "/cancelar", "{\"motivo\":\"desistiu\"}").andExpect(status().isOk());
        apagar(c.admin(), "/api/clientes/" + cancelada).andExpect(status().isNoContent());
        assertThat(processo(c, p2).get("status").asText()).isEqualTo("CANCELADO");
        apagar(c.admin(), "/api/clientes/999999").andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ entregas

    @Test
    void entregaApagadaSaiDosSetores_soProcessoEmAndamentoBloqueia() throws Exception {
        Cenario c = cenario();
        long extra = corpo(enviar(c.gestor(), "/api/entregas", "{\"nome\":\"Laudo\",\"fluxoId\":" + c.fluxoId() + "}")
                .andExpect(status().isCreated())).get("id").asLong();
        long setor = corpo(enviar(c.admin(), "/api/setores", "{\"nome\":\"Engenharia\",\"entregaIds\":[" + extra + "," + c.entregaPadraoId() + "]}")
                .andExpect(status().isCreated())).get("id").asLong();

        apagar(c.usuario(), "/api/entregas/" + extra).andExpect(status().isForbidden());
        apagar(c.gestor(), "/api/entregas/" + extra).andExpect(status().isNoContent());
        for (JsonNode s : consultar(c.admin(), "/api/setores")) {
            if (s.get("id").asLong() == setor) {
                assertThat(s.get("entregas")).hasSize(1);                         // só sobrou a entrega padrão
                assertThat(s.get("entregas").get(0).get("nome").asText()).isEqualTo("Atendimento geral");
            }
        }
        assertThat(consultar(c.admin(), "/api/entregas")).hasSize(1);

        long p = processo(c, cliente(c.admin(), "Cliente"), c.adminId());          // usa a entrega padrão
        var resp = corpo(apagar(c.admin(), "/api/entregas/" + c.entregaPadraoId()).andExpect(status().isUnprocessableEntity()));
        assertThat(resp.get("erro").asText()).contains("1 processo").contains("em andamento");
        assertThat(consultar(c.admin(), "/api/entregas")).hasSize(1);

        concluir(c, p);                                                             // concluído libera
        apagar(c.gestor(), "/api/entregas/" + c.entregaPadraoId()).andExpect(status().isNoContent());
        assertThat(consultar(c.admin(), "/api/entregas")).isEmpty();
        JsonNode depois = processo(c, p);
        assertThat(depois.get("entregaNome").isNull()).isTrue();
        assertThat(depois.get("status").asText()).isEqualTo("CONCLUIDO");
    }

    // ------------------------------------------------------------------ setores

    @Test
    void setorApagadoDeixaFuncionariosSemSetor_soProcessoEmAndamentoBloqueia() throws Exception {
        Cenario c = cenario();
        long setor = corpo(enviar(c.gestor(), "/api/setores", "{\"nome\":\"Financeiro\"}").andExpect(status().isCreated())).get("id").asLong();
        alterar(c.admin(), "/api/usuarios/" + c.usuarioId(), "{\"setorId\":" + setor + "}").andExpect(status().isOk());

        apagar(c.usuario(), "/api/setores/" + setor).andExpect(status().isForbidden());
        apagar(c.gestor(), "/api/setores/" + setor).andExpect(status().isNoContent());
        assertThat(consultar(c.admin(), "/api/setores")).hasSize(1);
        for (JsonNode u : consultar(c.admin(), "/api/usuarios")) {
            if (u.get("id").asLong() == c.usuarioId()) assertThat(u.get("setorNome").isNull()).isTrue();   // ficou sem setor
        }

        long p = processo(c, cliente(c.admin(), "Cliente"), c.adminId());          // usa o setor Geral
        var resp = corpo(apagar(c.admin(), "/api/setores/" + c.setorGeralId()).andExpect(status().isUnprocessableEntity()));
        assertThat(resp.get("erro").asText()).contains("1 processo").contains("em andamento");
        assertThat(consultar(c.admin(), "/api/setores")).hasSize(1);

        concluir(c, p);
        apagar(c.admin(), "/api/setores/" + c.setorGeralId()).andExpect(status().isNoContent());
        assertThat(processo(c, p).get("setorNome").isNull()).isTrue();
        assertThat(processo(c, p).get("status").asText()).isEqualTo("CONCLUIDO");
    }

    // ------------------------------------------------------------------ funcionários

    @Test
    void gestorApagaFuncionarioUsuario_masNaoAdminNemGestor_ninguemApagaASiMesmo() throws Exception {
        Cenario c = cenario();
        apagar(c.usuario(), "/api/usuarios/" + c.gestorId()).andExpect(status().isForbidden());
        apagar(c.gestor(), "/api/usuarios/" + c.adminId()).andExpect(status().isForbidden());
        apagar(c.gestor(), "/api/usuarios/" + c.gestorId()).andExpect(status().isForbidden());
        apagar(c.admin(), "/api/usuarios/" + c.adminId()).andExpect(status().isUnprocessableEntity());   // a si mesmo

        apagar(c.gestor(), "/api/usuarios/" + c.usuarioId()).andExpect(status().isNoContent());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)                     // apagado não entra
                .content("{\"email\":\"" + c.emailUsuario() + "\",\"senha\":\"senha12345\"}")).andExpect(status().isUnauthorized());
        assertThat(consultar(c.admin(), "/api/usuarios")).hasSize(2);

        apagar(c.admin(), "/api/usuarios/" + c.gestorId()).andExpect(status().isNoContent());           // admin apaga gestor
        assertThat(consultar(c.admin(), "/api/usuarios")).hasSize(1);
        apagar(c.admin(), "/api/usuarios/999999").andExpect(status().isNotFound());
    }

    @Test
    void funcionarioResponsavelPorProcessoEmAndamentoNaoApaga_encerradoNaoImpede() throws Exception {
        Cenario c = cenario();
        long p = processo(c, cliente(c.admin(), "Cliente"), c.usuarioId());
        var resp = corpo(apagar(c.gestor(), "/api/usuarios/" + c.usuarioId()).andExpect(status().isUnprocessableEntity()));
        assertThat(resp.get("erro").asText()).contains("responsável").contains("1 processo").contains("em andamento");

        mvc.perform(put("/api/processos/" + p + "/responsavel").header("Authorization", "Bearer " + c.gestor())
                .contentType(MediaType.APPLICATION_JSON).content("{\"responsavelId\":" + c.adminId() + "}")).andExpect(status().isOk());
        apagar(c.gestor(), "/api/usuarios/" + c.usuarioId()).andExpect(status().isNoContent());

        // processo concluído: o responsável pode ser apagado sem precisar passar o processo adiante
        long outro = corpo(enviar(c.admin(), "/api/usuarios", "{\"nome\":\"Outro\",\"email\":\"outro-" + UUID.randomUUID()
                + "@t.com\",\"senha\":\"senha12345\",\"papel\":\"USUARIO\",\"setorId\":" + c.setorGeralId() + "}").andExpect(status().isCreated())).get("id").asLong();
        long p2 = processo(c, cliente(c.admin(), "Outro cliente"), outro);
        concluir(c, p2);
        apagar(c.gestor(), "/api/usuarios/" + outro).andExpect(status().isNoContent());
        JsonNode depois = processo(c, p2);
        assertThat(depois.get("status").asText()).isEqualTo("CONCLUIDO");
        assertThat(depois.get("responsavelNome").asText()).isEqualTo("Outro (removido)");   // o nome continua visível
    }

    @Test
    void historicoPermaneceQuandoOFuncionarioQueAgiuEApagado() throws Exception {
        Cenario c = cenario();
        long p = processo(c, cliente(c.admin(), "Cliente"), c.usuarioId());
        enviar(c.usuario(), "/api/processos/" + p + "/comentarios", "{\"texto\":\"feito por quem sairá\"}").andExpect(status().isCreated());
        mvc.perform(put("/api/processos/" + p + "/responsavel").header("Authorization", "Bearer " + c.admin())
                .contentType(MediaType.APPLICATION_JSON).content("{\"responsavelId\":" + c.adminId() + "}")).andExpect(status().isOk());
        apagar(c.admin(), "/api/usuarios/" + c.usuarioId()).andExpect(status().isNoContent());

        JsonNode hist = consultar(c.admin(), "/api/processos/" + p).get("historico");
        JsonNode comentario = null;
        for (JsonNode h : hist) if (h.get("acao").asText().equals("COMENTARIO")) comentario = h;
        assertThat(comentario).isNotNull();
        assertThat(comentario.get("comentario").asText()).isEqualTo("feito por quem sairá");   // registro continua
        assertThat(comentario.get("usuarioNome").isNull()).isTrue();                          // autor não existe mais
    }

    // ------------------------------------------------------------------ fluxos

    private long fluxo(String token, String nome) throws Exception {
        return corpo(enviar(token, "/api/fluxos", "{\"nome\":\"" + nome + "\",\"etapas\":["
                + "{\"nome\":\"Inicio\",\"prazoDias\":1,\"inicial\":true,\"finalEtapa\":false},"
                + "{\"nome\":\"Fim\",\"inicial\":false,\"finalEtapa\":true}]}").andExpect(status().isCreated())).get("id").asLong();
    }

    @Test
    void fluxoNaoApagaComEntregaOuProcessoEmAndamento_encerradoNaoImpede() throws Exception {
        Cenario c = cenario();
        long f2 = fluxo(c.gestor(), "Fluxo curto");
        apagar(c.usuario(), "/api/fluxos/" + f2).andExpect(status().isForbidden());

        // uma entrega usa o fluxo: não apaga
        long entrega = corpo(enviar(c.admin(), "/api/entregas", "{\"nome\":\"Curta\",\"fluxoId\":" + f2 + "}")
                .andExpect(status().isCreated())).get("id").asLong();
        long setor = corpo(enviar(c.admin(), "/api/setores", "{\"nome\":\"Curto\",\"entregaIds\":[" + entrega + "]}")
                .andExpect(status().isCreated())).get("id").asLong();
        var porEntrega = corpo(apagar(c.admin(), "/api/fluxos/" + f2).andExpect(status().isUnprocessableEntity()));
        assertThat(porEntrega.get("erro").asText()).contains("1 entrega").contains("Curta");

        // processo em andamento nesse fluxo: não apaga, mesmo depois de tirar a entrega dele
        long cliente = cliente(c.admin(), "Cliente");
        long p = corpo(enviar(c.admin(), "/api/processos", "{\"clienteId\":" + cliente + ",\"entregaId\":" + entrega
                + ",\"setorId\":" + setor + ",\"responsavelId\":" + c.adminId() + "}").andExpect(status().isCreated())).get("id").asLong();
        alterar(c.admin(), "/api/entregas/" + entrega, "{\"fluxoId\":" + c.fluxoId() + "}").andExpect(status().isOk());
        var porProcesso = corpo(apagar(c.admin(), "/api/fluxos/" + f2).andExpect(status().isUnprocessableEntity()));
        assertThat(porProcesso.get("erro").asText()).contains("1 processo").contains("em andamento");
        assertThat(consultar(c.admin(), "/api/fluxos")).hasSize(2);

        // processo concluído não impede: o fluxo sai e o processo continua, sem fluxo
        concluir(c, p);
        apagar(c.gestor(), "/api/fluxos/" + f2).andExpect(status().isNoContent());
        assertThat(consultar(c.admin(), "/api/fluxos")).hasSize(1);
        JsonNode det = consultar(c.admin(), "/api/processos/" + p);               // abrir o processo não pode quebrar
        assertThat(det.get("processo").get("status").asText()).isEqualTo("CONCLUIDO");
        assertThat(det.get("processo").get("fluxoNome").isNull()).isTrue();
        assertThat(det.get("processo").get("etapaAtual").isNull()).isTrue();
        boolean temMovimento = false;                                              // o histórico guarda os nomes das etapas
        for (JsonNode h : det.get("historico")) if (h.get("acao").asText().equals("MOVIDO") && h.get("paraEtapa").asText().equals("Fim")) temMovimento = true;
        assertThat(temMovimento).isTrue();
        assertThat(consultar(c.admin(), "/api/processos").get("content")).hasSize(1);   // a lista também continua funcionando

        // processo cancelado também não impede
        long f3 = fluxo(c.admin(), "Fluxo cancelado");
        long entrega3 = corpo(enviar(c.admin(), "/api/entregas", "{\"nome\":\"Tres\",\"fluxoId\":" + f3 + "}").andExpect(status().isCreated())).get("id").asLong();
        long setor3 = corpo(enviar(c.admin(), "/api/setores", "{\"nome\":\"Setor tres\",\"entregaIds\":[" + entrega3 + "]}").andExpect(status().isCreated())).get("id").asLong();
        long p3 = corpo(enviar(c.admin(), "/api/processos", "{\"clienteId\":" + cliente + ",\"entregaId\":" + entrega3
                + ",\"setorId\":" + setor3 + ",\"responsavelId\":" + c.adminId() + "}").andExpect(status().isCreated())).get("id").asLong();
        enviar(c.admin(), "/api/processos/" + p3 + "/cancelar", "{\"motivo\":\"x\"}").andExpect(status().isOk());
        apagar(c.admin(), "/api/entregas/" + entrega3).andExpect(status().isNoContent());   // libera o fluxo
        apagar(c.admin(), "/api/fluxos/" + f3).andExpect(status().isNoContent());
        assertThat(consultar(c.admin(), "/api/processos/" + p3).get("processo").get("status").asText()).isEqualTo("CANCELADO");

        // o fluxo da entrega padrão continua protegido; inexistente dá 404
        apagar(c.admin(), "/api/fluxos/" + c.fluxoId()).andExpect(status().isUnprocessableEntity());
        apagar(c.admin(), "/api/fluxos/999999").andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ isolamento

    @Test
    void naoApagaCadastrosDeOutraEmpresa() throws Exception {
        Cenario a = cenario();
        Cenario b = cenario();
        long cliente = cliente(a.admin(), "Só da A");
        apagar(b.admin(), "/api/clientes/" + cliente).andExpect(status().isNotFound());
        apagar(b.admin(), "/api/setores/" + a.setorGeralId()).andExpect(status().isNotFound());
        apagar(b.admin(), "/api/entregas/" + a.entregaPadraoId()).andExpect(status().isNotFound());
        apagar(b.admin(), "/api/usuarios/" + a.usuarioId()).andExpect(status().isNotFound());
        apagar(b.admin(), "/api/fluxos/" + a.fluxoId()).andExpect(status().isNotFound());
        assertThat(consultar(a.admin(), "/api/clientes")).hasSize(1);
        assertThat(consultar(a.admin(), "/api/usuarios")).hasSize(3);
    }
}
