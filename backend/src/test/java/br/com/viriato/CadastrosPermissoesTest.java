package br.com.viriato;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CadastrosPermissoesTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    /** Empresa com um admin, um gestor e um usuário comum já criados. */
    record Cenario(String admin, String gestor, String usuario, long fluxoId, long setorGeralId, long entregaPadraoId) {}

    private JsonNode corpo(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }

    private ResultActions enviar(String token, String url, String body) throws Exception {
        return mvc.perform(post(url).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions alterar(String token, String url, String body) throws Exception {
        return mvc.perform(patch(url).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private JsonNode consultar(String token, String url) throws Exception {
        return corpo(mvc.perform(get(url).header("Authorization", "Bearer " + token)).andExpect(status().isOk()));
    }

    private String login(String email) throws Exception {
        return corpo(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"senha\":\"senha12345\"}")).andExpect(status().isOk()))
                .get("token").asText();
    }

    private Cenario cenario() throws Exception {
        String sufixo = UUID.randomUUID().toString();
        var reg = corpo(mvc.perform(post("/api/auth/registrar-empresa").contentType(MediaType.APPLICATION_JSON)
                .content("{\"empresa\":\"Emp\",\"nome\":\"Adm\",\"email\":\"adm-" + sufixo + "@t.com\",\"senha\":\"senha12345\"}"))
                .andExpect(status().isCreated()));
        String admin = reg.get("token").asText();
        enviar(admin, "/api/usuarios", "{\"nome\":\"Gestor\",\"email\":\"ges-" + sufixo + "@t.com\",\"senha\":\"senha12345\",\"papel\":\"GESTOR\"}")
                .andExpect(status().isCreated());
        enviar(admin, "/api/usuarios", "{\"nome\":\"Func\",\"email\":\"usu-" + sufixo + "@t.com\",\"senha\":\"senha12345\",\"papel\":\"USUARIO\"}")
                .andExpect(status().isCreated());
        long fluxo = consultar(admin, "/api/fluxos").get(0).get("id").asLong();
        JsonNode geral = consultar(admin, "/api/setores").get(0);
        return new Cenario(admin, login("ges-" + sufixo + "@t.com"), login("usu-" + sufixo + "@t.com"), fluxo,
                geral.get("id").asLong(), geral.get("entregas").get(0).get("id").asLong());
    }

    @Test
    void onboardingCriaSetorGeralComEntregaPadrao() throws Exception {
        Cenario c = cenario();
        JsonNode setores = consultar(c.admin(), "/api/setores");
        assertThat(setores).hasSize(1);
        assertThat(setores.get(0).get("nome").asText()).isEqualTo("Geral");
        assertThat(setores.get(0).get("entregas").get(0).get("nome").asText()).isEqualTo("Atendimento geral");
        assertThat(consultar(c.admin(), "/api/clientes")).isEmpty(); // empresas atendidas: o admin cadastra
    }

    @Test
    void adminEGestorCadastramEmpresas_usuarioSoVe() throws Exception {
        Cenario c = cenario();
        enviar(c.admin(), "/api/clientes", "{\"numero\":\"101\",\"nome\":\"Construtora Alfa\"}").andExpect(status().isCreated());
        long doGestor = corpo(enviar(c.gestor(), "/api/clientes", "{\"numero\":\"102\",\"nome\":\"Beta Engenharia\"}")
                .andExpect(status().isCreated())).get("id").asLong();
        alterar(c.gestor(), "/api/clientes/" + doGestor, "{\"nome\":\"Beta Engenharia S/A\"}").andExpect(status().isOk());
        enviar(c.usuario(), "/api/clientes", "{\"numero\":\"103\",\"nome\":\"Outra\"}").andExpect(status().isForbidden());
        alterar(c.usuario(), "/api/clientes/" + doGestor, "{\"ativo\":false}").andExpect(status().isForbidden());
        // todos enxergam a lista
        assertThat(consultar(c.usuario(), "/api/clientes")).hasSize(2);
        // nome repetido (sem diferenciar maiúsculas) é recusado
        enviar(c.admin(), "/api/clientes", "{\"numero\":\"104\",\"nome\":\"construtora alfa\"}").andExpect(status().isUnprocessableEntity());
    }

    @Test
    void adminEGestorCadastramSetoresEEntregas_usuarioSoVe() throws Exception {
        Cenario c = cenario();
        long entrega = corpo(enviar(c.gestor(), "/api/entregas", "{\"nome\":\"Laudo técnico\",\"fluxoId\":" + c.fluxoId() + "}")
                .andExpect(status().isCreated())).get("id").asLong();
        var setor = corpo(enviar(c.admin(), "/api/setores", "{\"nome\":\"Engenharia\",\"entregaIds\":[" + entrega + "]}")
                .andExpect(status().isCreated()));
        assertThat(setor.get("entregas").get(0).get("nome").asText()).isEqualTo("Laudo técnico");

        // gestor altera as entregas do setor
        long setorId = setor.get("id").asLong();
        var atualizado = corpo(alterar(c.gestor(), "/api/setores/" + setorId,
                "{\"entregaIds\":[" + entrega + "," + c.entregaPadraoId() + "]}").andExpect(status().isOk()));
        assertThat(atualizado.get("entregas")).hasSize(2);

        // usuário comum não altera nada
        enviar(c.usuario(), "/api/setores", "{\"nome\":\"X\"}").andExpect(status().isForbidden());
        enviar(c.usuario(), "/api/entregas", "{\"nome\":\"X\",\"fluxoId\":" + c.fluxoId() + "}").andExpect(status().isForbidden());
        alterar(c.usuario(), "/api/setores/" + setorId, "{\"ativo\":false}").andExpect(status().isForbidden());
        assertThat(consultar(c.usuario(), "/api/setores")).hasSize(2);
    }

    @Test
    void gestorAdicionaFuncionarioMasSoComPapelUsuario() throws Exception {
        Cenario c = cenario();
        String email = "novo-" + UUID.randomUUID() + "@t.com";
        enviar(c.gestor(), "/api/usuarios", "{\"nome\":\"Novo\",\"email\":\"" + email
                + "\",\"senha\":\"senha12345\",\"papel\":\"USUARIO\",\"setorId\":" + c.setorGeralId() + "}")
                .andExpect(status().isCreated());
        enviar(c.gestor(), "/api/usuarios", "{\"nome\":\"Chefe\",\"email\":\"x-" + email
                + "\",\"senha\":\"senha12345\",\"papel\":\"GESTOR\"}").andExpect(status().isForbidden());
        enviar(c.gestor(), "/api/usuarios", "{\"nome\":\"Adm\",\"email\":\"y-" + email
                + "\",\"senha\":\"senha12345\",\"papel\":\"ADMIN\"}").andExpect(status().isForbidden());
        enviar(c.usuario(), "/api/usuarios", "{\"nome\":\"Z\",\"email\":\"z-" + email
                + "\",\"senha\":\"senha12345\",\"papel\":\"USUARIO\"}").andExpect(status().isForbidden());
    }

    @Test
    void gestorAlteraSituacaoDeUsuarioMasNaoSetorNemPapel_usuarioSoVe() throws Exception {
        Cenario c = cenario();
        long idFunc = 0, idAdmin = 0, idGestor = 0;
        for (JsonNode u : consultar(c.usuario(), "/api/usuarios")) { // o usuário comum enxerga a equipe
            switch (u.get("nome").asText()) {
                case "Func" -> idFunc = u.get("id").asLong();
                case "Adm" -> idAdmin = u.get("id").asLong();
                case "Gestor" -> idGestor = u.get("id").asLong();
                default -> { }
            }
        }
        String emailFunc = null;
        for (JsonNode u : consultar(c.admin(), "/api/usuarios")) if (u.get("id").asLong() == idFunc) emailFunc = u.get("email").asText();

        // gestor desativa e reativa um funcionário (papel Usuário)
        alterar(c.gestor(), "/api/usuarios/" + idFunc, "{\"ativo\":false}").andExpect(status().isOk());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)       // desativado não entra
                .content("{\"email\":\"" + emailFunc + "\",\"senha\":\"senha12345\"}")).andExpect(status().isUnauthorized());
        alterar(c.gestor(), "/api/usuarios/" + idFunc, "{\"ativo\":true}").andExpect(status().isOk());
        login(emailFunc);

        // mas não mexe no setor (nem para trocar, nem para remover) nem no papel
        alterar(c.gestor(), "/api/usuarios/" + idFunc, "{\"setorId\":" + c.setorGeralId() + "}").andExpect(status().isForbidden());
        alterar(c.gestor(), "/api/usuarios/" + idFunc, "{\"removerSetor\":true}").andExpect(status().isForbidden());
        alterar(c.gestor(), "/api/usuarios/" + idFunc, "{\"papel\":\"GESTOR\"}").andExpect(status().isForbidden());

        // e não desativa quem não é Usuário (administrador ou outro gestor)
        alterar(c.gestor(), "/api/usuarios/" + idAdmin, "{\"ativo\":false}").andExpect(status().isForbidden());
        alterar(c.gestor(), "/api/usuarios/" + idGestor, "{\"ativo\":false}").andExpect(status().isForbidden());

        // usuário comum não altera nada
        alterar(c.usuario(), "/api/usuarios/" + idFunc, "{\"ativo\":false}").andExpect(status().isForbidden());
        alterar(c.usuario(), "/api/usuarios/" + idFunc, "{\"setorId\":" + c.setorGeralId() + "}").andExpect(status().isForbidden());

        // o administrador define o setor e o papel
        alterar(c.admin(), "/api/usuarios/" + idFunc, "{\"setorId\":" + c.setorGeralId() + "}").andExpect(status().isOk());
        JsonNode func = null;
        for (JsonNode u : consultar(c.usuario(), "/api/usuarios")) if (u.get("id").asLong() == idFunc) func = u;
        assertThat(func.get("setorNome").asText()).isEqualTo("Geral");
        assertThat(func.get("papel").asText()).isEqualTo("USUARIO");
        alterar(c.admin(), "/api/usuarios/" + idFunc, "{\"papel\":\"GESTOR\"}").andExpect(status().isOk());
    }

    @Test
    void cadastrosDeUmaEmpresaNaoVazamParaOutra() throws Exception {
        Cenario a = cenario();
        Cenario b = cenario();
        long clienteA = corpo(enviar(a.admin(), "/api/clientes", "{\"numero\":\"105\",\"nome\":\"Só da A\"}").andExpect(status().isCreated())).get("id").asLong();

        assertThat(consultar(b.admin(), "/api/clientes")).isEmpty();
        alterar(b.admin(), "/api/clientes/" + clienteA, "{\"ativo\":false}").andExpect(status().isNotFound());
        // entrega usando fluxo de outra empresa
        enviar(b.admin(), "/api/entregas", "{\"nome\":\"E\",\"fluxoId\":" + a.fluxoId() + "}").andExpect(status().isNotFound());
        // setor com entrega de outra empresa
        enviar(b.admin(), "/api/setores", "{\"nome\":\"S\",\"entregaIds\":[" + a.entregaPadraoId() + "]}")
                .andExpect(status().isUnprocessableEntity());
        // usuário com setor de outra empresa
        enviar(b.admin(), "/api/usuarios", "{\"nome\":\"U\",\"email\":\"u-" + UUID.randomUUID()
                + "@t.com\",\"senha\":\"senha12345\",\"papel\":\"USUARIO\",\"setorId\":" + a.setorGeralId() + "}")
                .andExpect(status().isUnprocessableEntity());
    }
}
