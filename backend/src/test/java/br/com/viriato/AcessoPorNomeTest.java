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

/** Acesso por nome e senha, primeiro acesso com troca obrigatória, setor do gestor e zerar senha pelo administrador. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AcessoPorNomeTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    record C(String sfx, String admin, String adminNome, String gestor, String gestorNome, long setorId, long outroSetorId) {}

    private JsonNode corpo(ResultActions r) throws Exception { return json.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)); }
    private ResultActions enviar(String t, String url, String b) throws Exception {
        return mvc.perform(post(url).header("Authorization", "Bearer " + t).contentType(MediaType.APPLICATION_JSON).content(b));
    }
    private ResultActions ler(String t, String url) throws Exception { return mvc.perform(get(url).header("Authorization", "Bearer " + t)); }
    private ResultActions login(String nome, String senha) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"nome\":\"" + nome + "\",\"senha\":\"" + senha + "\"}"));
    }
    private ResultActions trocar(String token, String nova) throws Exception {
        return mvc.perform(put("/api/me/senha").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"novaSenha\":\"" + nova + "\"}"));
    }
    private ResultActions zerar(String token, long id, String senha) throws Exception {
        return mvc.perform(put("/api/usuarios/" + id + "/senha").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"senha\":\"" + senha + "\"}"));
    }

    /** Admin (sem e-mail de acesso: entra pelo nome) e gestor do setor Geral, ambos já com senha própria. */
    private C cenario() throws Exception {
        String s = UUID.randomUUID().toString().substring(0, 8);
        String adminNome = "Admin-" + s, gestorNome = "Gestor-" + s;
        mvc.perform(post("/api/auth/registrar-empresa").contentType(MediaType.APPLICATION_JSON)
                .content("{\"empresa\":\"E\",\"nome\":\"" + adminNome + "\",\"email\":\"a-" + s + "@t.com\",\"senha\":\"senha12345\"}")).andExpect(status().isCreated());
        String admin = corpo(login(adminNome, "senha12345").andExpect(status().isOk())).get("token").asText();
        long setor = corpo(ler(admin, "/api/setores")).get(0).get("id").asLong();
        long outro = corpo(enviar(admin, "/api/setores", "{\"nome\":\"Outro\",\"entregaIds\":[]}").andExpect(status().isCreated())).get("id").asLong();
        enviar(admin, "/api/usuarios", "{\"nome\":\"" + gestorNome + "\",\"senha\":\"senha12345\",\"papel\":\"GESTOR\",\"setorId\":" + setor + "}").andExpect(status().isCreated());
        String gestor = TestesAuth.entrar(mvc, json, gestorNome);
        return new C(s, admin, adminNome, gestor, gestorNome, setor, outro);
    }

    @Test
    void entraSoComNomeESenha_semDiferenciarMaiusculas_eRecusaSenhaErrada() throws Exception {
        C c = cenario();
        var s = corpo(login(c.gestorNome().toUpperCase(), TestesAuth.SENHA_PROPRIA).andExpect(status().isOk()));
        assertThat(s.get("usuario").get("nome").asText()).isEqualTo(c.gestorNome());
        assertThat(s.get("usuario").has("email")).isFalse();                       // e-mail não é exposto
        login(c.gestorNome(), "errada-12345").andExpect(status().isUnauthorized());
        login("Ninguem-" + c.sfx(), "senha12345").andExpect(status().isUnauthorized());
        assertThat(corpo(login("x", "y").andExpect(status().isUnauthorized())).get("erro").asText()).isEqualTo("Nome ou senha inválidos");
    }

    @Test
    void gestorCriaFuncionarioSoComNomeSenhaEPapel_comOSetorDele() throws Exception {
        C c = cenario();
        String nome = "Novo-" + c.sfx();
        // sem e-mail; mesmo mandando outro setor, vale o do gestor
        var novo = corpo(enviar(c.gestor(), "/api/usuarios", "{\"nome\":\"" + nome + "\",\"senha\":\"inicial-123\",\"papel\":\"USUARIO\",\"setorId\":" + c.outroSetorId() + "}")
                .andExpect(status().isCreated()));
        assertThat(novo.get("setorId").asLong()).isEqualTo(c.setorId());
        assertThat(novo.get("setorNome").asText()).isEqualTo("Geral");
        assertThat(novo.get("deveTrocarSenha").asBoolean()).isTrue();
        // o nome identifica a pessoa: não repete (nem com outra caixa) e não aceita @
        assertThat(corpo(enviar(c.gestor(), "/api/usuarios", "{\"nome\":\"" + nome.toUpperCase() + "\",\"senha\":\"inicial-123\",\"papel\":\"USUARIO\"}")
                .andExpect(status().isUnprocessableEntity())).get("erro").asText()).contains("nome");
        enviar(c.gestor(), "/api/usuarios", "{\"nome\":\"a@b\",\"senha\":\"inicial-123\",\"papel\":\"USUARIO\"}").andExpect(status().isUnprocessableEntity());
        // gestor sem setor não cria funcionário
        String semSetor = "Sem-" + c.sfx();
        enviar(c.admin(), "/api/usuarios", "{\"nome\":\"" + semSetor + "\",\"senha\":\"senha12345\",\"papel\":\"GESTOR\"}").andExpect(status().isCreated());
        String tokenSem = TestesAuth.entrar(mvc, json, semSetor);
        enviar(tokenSem, "/api/usuarios", "{\"nome\":\"Outro-" + c.sfx() + "\",\"senha\":\"inicial-123\",\"papel\":\"USUARIO\"}").andExpect(status().isUnprocessableEntity());
    }

    @Test
    void primeiroAcessoExigeSenhaPropria_eSoDepoisLiberaOSistema() throws Exception {
        C c = cenario();
        String nome = "Func-" + c.sfx();
        enviar(c.gestor(), "/api/usuarios", "{\"nome\":\"" + nome + "\",\"senha\":\"inicial-123\",\"papel\":\"USUARIO\"}").andExpect(status().isCreated());

        var s = corpo(login(nome, "inicial-123").andExpect(status().isOk()));
        String token = s.get("token").asText();
        assertThat(s.get("usuario").get("deveTrocarSenha").asBoolean()).isTrue();

        // enquanto a senha for a inicial, só /me e a troca respondem; o resto é bloqueado
        ler(token, "/api/me").andExpect(status().isOk());
        var bloqueio = corpo(ler(token, "/api/processos").andExpect(status().isForbidden()));
        assertThat(bloqueio.get("codigo").asText()).isEqualTo("TROCAR_SENHA");
        ler(token, "/api/usuarios").andExpect(status().isForbidden());

        // a nova senha precisa ser válida e diferente da inicial
        trocar(token, "curta").andExpect(status().isBadRequest());
        assertThat(corpo(trocar(token, "inicial-123").andExpect(status().isUnprocessableEntity())).get("erro").asText()).contains("diferente");
        var depois = corpo(trocar(token, "minha-senha-boa-1").andExpect(status().isOk()));
        assertThat(depois.get("deveTrocarSenha").asBoolean()).isFalse();

        // liberado: o mesmo token já funciona; a senha inicial deixa de valer e a nova passa a valer
        ler(token, "/api/processos").andExpect(status().isOk());
        login(nome, "inicial-123").andExpect(status().isUnauthorized());
        login(nome, "minha-senha-boa-1").andExpect(status().isOk());
        // trocar de novo por esse caminho não é permitido (já tem senha própria)
        trocar(token, "outra-senha-boa-2").andExpect(status().isUnprocessableEntity());
    }

    @Test
    void soOAdminZeraSenha_novaSenhaVaiNaHora_ePessoaTrocaNoProximoAcesso() throws Exception {
        C c = cenario();
        String nome = "Func-" + c.sfx();
        long id = corpo(enviar(c.gestor(), "/api/usuarios", "{\"nome\":\"" + nome + "\",\"senha\":\"inicial-123\",\"papel\":\"USUARIO\"}").andExpect(status().isCreated())).get("id").asLong();
        String tokenFunc = corpo(login(nome, "inicial-123").andExpect(status().isOk())).get("token").asText();
        trocar(tokenFunc, "senha-do-func-1").andExpect(status().isOk());
        ler(tokenFunc, "/api/processos").andExpect(status().isOk());

        // gestor não zera; funcionário também não
        zerar(c.gestor(), id, "zerada-12345").andExpect(status().isForbidden());
        zerar(tokenFunc, id, "zerada-12345").andExpect(status().isForbidden());
        // a nova senha é obrigatória e tem tamanho mínimo; não vale para a própria conta; id inexistente é 404
        mvc.perform(put("/api/usuarios/" + id + "/senha").header("Authorization", "Bearer " + c.admin()).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        zerar(c.admin(), id, "curta").andExpect(status().isBadRequest());
        long idAdmin = corpo(ler(c.admin(), "/api/me")).get("id").asLong();
        zerar(c.admin(), idAdmin, "zerada-12345").andExpect(status().isUnprocessableEntity());
        zerar(c.admin(), 999999, "zerada-12345").andExpect(status().isNotFound());

        // o admin zera (e já informa a nova senha provisória)
        zerar(c.admin(), id, "zerada-12345").andExpect(status().isNoContent());
        login(nome, "senha-do-func-1").andExpect(status().isUnauthorized());              // a senha antiga morreu
        ler(tokenFunc, "/api/processos").andExpect(status().isUnauthorized());           // a sessão antiga é encerrada
        ler(tokenFunc, "/api/me").andExpect(status().isUnauthorized());
        trocar(tokenFunc, "invasor-12345").andExpect(status().isUnauthorized());          // e não dá para tomar a conta com ela
        var s = corpo(login(nome, "zerada-12345").andExpect(status().isOk()));
        assertThat(s.get("usuario").get("deveTrocarSenha").asBoolean()).isTrue();         // e a pessoa troca no próximo acesso
        trocar(s.get("token").asText(), "zerada-12345").andExpect(status().isUnprocessableEntity());
        trocar(s.get("token").asText(), "nova-propria-77").andExpect(status().isOk());
        login(nome, "nova-propria-77").andExpect(status().isOk());
    }

    @Test
    void adminTambemZeraSenhaDeGestorEDeOutroAdmin_eDesativadoPerdeAcessoNaHora() throws Exception {
        C c = cenario();
        String outroAdmin = "Adm2-" + c.sfx();
        long idAdm2 = corpo(enviar(c.admin(), "/api/usuarios", "{\"nome\":\"" + outroAdmin + "\",\"senha\":\"senha12345\",\"papel\":\"ADMIN\"}").andExpect(status().isCreated())).get("id").asLong();
        long idGestor = corpo(ler(c.gestor(), "/api/me")).get("id").asLong();
        zerar(c.admin(), idAdm2, "zerada-adm-1").andExpect(status().isNoContent());
        zerar(c.admin(), idGestor, "zerada-ges-1").andExpect(status().isNoContent());
        assertThat(corpo(login(c.gestorNome(), "zerada-ges-1").andExpect(status().isOk())).get("usuario").get("deveTrocarSenha").asBoolean()).isTrue();
        assertThat(corpo(login(outroAdmin, "zerada-adm-1").andExpect(status().isOk())).get("usuario").get("deveTrocarSenha").asBoolean()).isTrue();

        // desativar corta a sessão que já estava aberta
        String nome = "Func-" + c.sfx();
        long id = corpo(enviar(c.admin(), "/api/usuarios", "{\"nome\":\"" + nome + "\",\"senha\":\"senha12345\",\"papel\":\"USUARIO\"}").andExpect(status().isCreated())).get("id").asLong();
        String t = TestesAuth.entrar(mvc, json, nome);
        ler(t, "/api/processos").andExpect(status().isOk());
        mvc.perform(patch("/api/usuarios/" + id).header("Authorization", "Bearer " + c.admin()).contentType(MediaType.APPLICATION_JSON).content("{\"ativo\":false}")).andExpect(status().isOk());
        ler(t, "/api/processos").andExpect(status().isUnauthorized());
    }
}
