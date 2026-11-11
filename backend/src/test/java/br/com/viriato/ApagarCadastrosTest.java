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

/** Apagar empresas, setores, entregas e funcionários: quem pode e o que o sistema protege. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApagarCadastrosTest {

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
        enviar(admin, "/api/usuarios", "{\"nome\":\"Gestor\",\"email\":\"ges-" + sfx + "@t.com\",\"senha\":\"senha12345\",\"papel\":\"GESTOR\"}").andExpect(status().isCreated());
        enviar(admin, "/api/usuarios", "{\"nome\":\"Func\",\"email\":\"usu-" + sfx + "@t.com\",\"senha\":\"senha12345\",\"papel\":\"USUARIO\"}").andExpect(status().isCreated());
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

    private long cliente(String token, String nome) throws Exception {
        return corpo(enviar(token, "/api/clientes", "{\"nome\":\"" + nome + "\"}").andExpect(status().isCreated())).get("id").asLong();
    }

    // ------------------------------------------------------------------ empresas

    @Test
    void empresaSemProcessoApaga_comProcessoNao_usuarioNaoApaga() throws Exception {
        Cenario c = cenario();
        long livre = cliente(c.gestor(), "Sem processos");
        long ocupada = cliente(c.admin(), "Com processos");
        processo(c, ocupada, c.adminId());

        apagar(c.usuario(), "/api/clientes/" + livre).andExpect(status().isForbidden());
        apagar(c.gestor(), "/api/clientes/" + livre).andExpect(status().isNoContent());
        assertThat(consultar(c.admin(), "/api/clientes")).hasSize(1);

        var resp = corpo(apagar(c.admin(), "/api/clientes/" + ocupada).andExpect(status().isUnprocessableEntity()));
        assertThat(resp.get("erro").asText()).contains("1 processo").contains("Desative o cadastro");
        assertThat(consultar(c.admin(), "/api/clientes")).hasSize(1);          // continua lá
        apagar(c.admin(), "/api/clientes/999999").andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ entregas

    @Test
    void entregaApagadaSaiDosSetores_comProcessoNaoApaga() throws Exception {
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

        processo(c, cliente(c.admin(), "Cliente"), c.adminId());                   // usa a entrega padrão
        var resp = corpo(apagar(c.admin(), "/api/entregas/" + c.entregaPadraoId()).andExpect(status().isUnprocessableEntity()));
        assertThat(resp.get("erro").asText()).contains("processo");
        assertThat(consultar(c.admin(), "/api/entregas")).hasSize(1);
    }

    // ------------------------------------------------------------------ setores

    @Test
    void setorApagadoDeixaFuncionariosSemSetor_comProcessoNaoApaga() throws Exception {
        Cenario c = cenario();
        long setor = corpo(enviar(c.gestor(), "/api/setores", "{\"nome\":\"Financeiro\"}").andExpect(status().isCreated())).get("id").asLong();
        alterar(c.admin(), "/api/usuarios/" + c.usuarioId(), "{\"setorId\":" + setor + "}").andExpect(status().isOk());

        apagar(c.usuario(), "/api/setores/" + setor).andExpect(status().isForbidden());
        apagar(c.gestor(), "/api/setores/" + setor).andExpect(status().isNoContent());
        assertThat(consultar(c.admin(), "/api/setores")).hasSize(1);
        for (JsonNode u : consultar(c.admin(), "/api/usuarios")) {
            if (u.get("id").asLong() == c.usuarioId()) assertThat(u.get("setorNome").isNull()).isTrue();   // ficou sem setor
        }

        processo(c, cliente(c.admin(), "Cliente"), c.adminId());                   // usa o setor Geral
        var resp = corpo(apagar(c.admin(), "/api/setores/" + c.setorGeralId()).andExpect(status().isUnprocessableEntity()));
        assertThat(resp.get("erro").asText()).contains("processo");
        assertThat(consultar(c.admin(), "/api/setores")).hasSize(1);
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
    void funcionarioResponsavelPorProcessoNaoApaga_atePassarOProcessoAdiante() throws Exception {
        Cenario c = cenario();
        long p = processo(c, cliente(c.admin(), "Cliente"), c.usuarioId());
        var resp = corpo(apagar(c.gestor(), "/api/usuarios/" + c.usuarioId()).andExpect(status().isUnprocessableEntity()));
        assertThat(resp.get("erro").asText()).contains("responsável").contains("1 processo");

        mvc.perform(put("/api/processos/" + p + "/responsavel").header("Authorization", "Bearer " + c.gestor())
                .contentType(MediaType.APPLICATION_JSON).content("{\"responsavelId\":" + c.adminId() + "}")).andExpect(status().isOk());
        apagar(c.gestor(), "/api/usuarios/" + c.usuarioId()).andExpect(status().isNoContent());
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
        assertThat(consultar(a.admin(), "/api/clientes")).hasSize(1);
        assertThat(consultar(a.admin(), "/api/usuarios")).hasSize(3);
    }
}
