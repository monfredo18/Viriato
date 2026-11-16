package br.com.viriato;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.viriato.seguranca.JwtService;
import br.com.viriato.seguranca.Totp;
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

/** Bloqueio de conta, desbloqueio, sair, troca de senha, verificação em duas etapas, auditoria e limite do chat. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SegurancaContaTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    record C(String admin, String gestor, String ana, String bia, String outroSetor, long anaId, long biaId, String s) {}

    private JsonNode corpo(ResultActions r) throws Exception { return json.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)); }
    private ResultActions enviar(String t, String url, String b) throws Exception {
        return mvc.perform(post(url).header("Authorization", "Bearer " + t).contentType(MediaType.APPLICATION_JSON).content(b));
    }
    private ResultActions ler(String t, String url) throws Exception { return mvc.perform(get(url).header("Authorization", "Bearer " + t)); }
    private ResultActions login(String nome, String senha, String codigo) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"nome\":\"" + nome + "\",\"senha\":\"" + senha + "\"" + (codigo == null ? "" : ",\"codigo\":\"" + codigo + "\"") + "}"));
    }
    private long criar(String admin, String nome, String papel, long setor) throws Exception {
        return corpo(enviar(admin, "/api/usuarios", "{\"nome\":\"" + nome + "\",\"senha\":\"senha12345\",\"papel\":\"" + papel + "\",\"setorId\":" + setor + "}").andExpect(status().isCreated())).get("id").asLong();
    }

    private C cenario() throws Exception {
        String s = UUID.randomUUID().toString().substring(0, 6);
        String admin = corpo(mvc.perform(post("/api/auth/registrar-empresa").contentType(MediaType.APPLICATION_JSON)
                .content("{\"empresa\":\"E\",\"nome\":\"Adm " + s + "\",\"email\":\"a-" + s + "@t.com\",\"senha\":\"senha12345\"}")).andExpect(status().isCreated())).get("token").asText();
        long geral = corpo(ler(admin, "/api/setores")).get(0).get("id").asLong();
        long fin = corpo(enviar(admin, "/api/setores", "{\"nome\":\"Financeiro\",\"entregaIds\":[]}").andExpect(status().isCreated())).get("id").asLong();
        criar(admin, "Gestor " + s, "GESTOR", geral);
        long ana = criar(admin, "Ana " + s, "USUARIO", geral);
        long bia = criar(admin, "Bia " + s, "USUARIO", fin);
        criar(admin, "Caio " + s, "USUARIO", geral);
        return new C(admin, TestesAuth.entrar(mvc, json, "Gestor " + s), TestesAuth.entrar(mvc, json, "Ana " + s), TestesAuth.entrar(mvc, json, "Bia " + s),
                TestesAuth.entrar(mvc, json, "Caio " + s), ana, bia, s);
    }

    private void errar(String nome, int vezes) throws Exception {
        for (int i = 0; i < vezes; i++) login(nome, "senha-errada-" + i, null).andExpect(status().isUnauthorized());
    }

    @Test
    void depoisDeCincoErrosASextaTentativaBloqueiaEOSuporteDesbloqueia() throws Exception {
        C c = cenario();
        String nome = "Ana " + c.s();
        errar(nome, 5);                                                                          // 5 erros ainda são "inválido"
        var bloqueio = corpo(login(nome, "senha-errada-x", null).andExpect(status().isLocked()));   // o 6º bloqueia
        assertThat(bloqueio.get("codigo").asText()).isEqualTo("CONTA_BLOQUEADA");
        assertThat(bloqueio.get("erro").asText()).contains("suporte");
        login(nome, TestesAuth.SENHA_PROPRIA, null).andExpect(status().isLocked());              // nem a senha certa entra
        // a lista mostra quem está bloqueado
        boolean achou = false;
        for (JsonNode u : corpo(ler(c.gestor(), "/api/usuarios"))) if (u.get("id").asLong() == c.anaId()) achou = u.get("bloqueado").asBoolean();
        assertThat(achou).isTrue();

        enviar(c.ana(), "/api/usuarios/" + c.anaId() + "/desbloquear", "").andExpect(status().isForbidden());      // usuário comum não
        enviar(c.gestor(), "/api/usuarios/" + c.biaId() + "/desbloquear", "").andExpect(status().isForbidden());    // gestor não desbloqueia outro setor
        enviar(c.gestor(), "/api/usuarios/" + c.anaId() + "/desbloquear", "").andExpect(status().isOk());           // gestor do mesmo setor
        enviar(c.admin(), "/api/usuarios/" + c.anaId() + "/desbloquear", "").andExpect(status().isUnprocessableEntity());   // já desbloqueada
        login(nome, TestesAuth.SENHA_PROPRIA, null).andExpect(status().isOk());
    }

    @Test
    void entrarComSucessoZeraAContagemEAdministradorDesbloqueiaQualquerUm() throws Exception {
        C c = cenario();
        String ana = "Ana " + c.s();
        errar(ana, 5);
        login(ana, TestesAuth.SENHA_PROPRIA, null).andExpect(status().isOk());                   // zera
        errar(ana, 5);                                                                           // não bloqueou
        login(ana, TestesAuth.SENHA_PROPRIA, null).andExpect(status().isOk());

        String bia = "Bia " + c.s();                                                             // de outro setor: só o administrador
        errar(bia, 5);
        login(bia, "x", null).andExpect(status().isLocked());
        enviar(c.admin(), "/api/usuarios/" + c.biaId() + "/desbloquear", "").andExpect(status().isOk());
        login(bia, TestesAuth.SENHA_PROPRIA, null).andExpect(status().isOk());
        login("Ninguém " + c.s(), "qualquer", null).andExpect(status().isUnauthorized());        // nome inexistente: erro comum, sem pista
    }

    @Test
    void zerarASenhaTambemDesbloqueia() throws Exception {
        C c = cenario();
        String ana = "Ana " + c.s();
        errar(ana, 5);
        login(ana, "x", null).andExpect(status().isLocked());
        mvc.perform(put("/api/usuarios/" + c.anaId() + "/senha").header("Authorization", "Bearer " + c.admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"senha\":\"outra-senha-123\"}")).andExpect(status().isNoContent());
        login(ana, "outra-senha-123", null).andExpect(status().isOk());
    }

    @Test
    void sairEncerraOTokenETrocarASenhaExigeAAtual() throws Exception {
        C c = cenario();
        ler(c.ana(), "/api/me").andExpect(status().isOk());
        enviar(c.ana(), "/api/me/sair", "").andExpect(status().isNoContent());
        ler(c.ana(), "/api/me").andExpect(status().isUnauthorized());                            // o token não volta a valer

        String nome = "Bia " + c.s();
        String t = c.bia();
        enviar(t, "/api/me/alterar-senha", "{\"senhaAtual\":\"errada-123\",\"novaSenha\":\"nova-senha-1\"}").andExpect(status().isUnprocessableEntity());
        enviar(t, "/api/me/alterar-senha", "{\"senhaAtual\":\"" + TestesAuth.SENHA_PROPRIA + "\",\"novaSenha\":\"" + TestesAuth.SENHA_PROPRIA + "\"}").andExpect(status().isUnprocessableEntity());
        enviar(t, "/api/me/alterar-senha", "{\"senhaAtual\":\"" + TestesAuth.SENHA_PROPRIA + "\",\"novaSenha\":\"curta\"}").andExpect(status().isBadRequest());
        String novoToken = corpo(enviar(t, "/api/me/alterar-senha", "{\"senhaAtual\":\"" + TestesAuth.SENHA_PROPRIA + "\",\"novaSenha\":\"nova-senha-1\"}").andExpect(status().isOk())).get("token").asText();
        ler(t, "/api/me").andExpect(status().isUnauthorized());                                  // sessão antiga encerrada
        ler(novoToken, "/api/me").andExpect(status().isOk());
        login(nome, "nova-senha-1", null).andExpect(status().isOk());
    }

    @Test
    void verificacaoEmDuasEtapas() throws Exception {
        C c = cenario();
        String nome = "Ana " + c.s();
        String segredo = corpo(enviar(c.ana(), "/api/me/2fa/iniciar", "").andExpect(status().isOk())).get("segredo").asText();
        login(nome, TestesAuth.SENHA_PROPRIA, null).andExpect(status().isOk());                  // ainda não ativada: entra normal
        enviar(c.ana(), "/api/me/2fa/ativar", "{\"codigo\":\"000000\"}").andExpect(status().isUnprocessableEntity());
        long agora = Totp.passoAtual();
        enviar(c.ana(), "/api/me/2fa/ativar", "{\"codigo\":\"" + Totp.codigo(segredo, agora) + "\"}").andExpect(status().isOk());

        var pede = corpo(login(nome, TestesAuth.SENHA_PROPRIA, null).andExpect(status().isForbidden()));
        assertThat(pede.get("codigo").asText()).isEqualTo("CODIGO_2FA");
        login(nome, TestesAuth.SENHA_PROPRIA, "123456").andExpect(status().isUnprocessableEntity());
        String proximo = Totp.codigo(segredo, agora + 1);                                        // período seguinte: aceito e ainda não usado
        login(nome, TestesAuth.SENHA_PROPRIA, proximo).andExpect(status().isOk());
        login(nome, TestesAuth.SENHA_PROPRIA, proximo).andExpect(status().isUnprocessableEntity());   // o mesmo código não vale duas vezes
        login(nome, "senha-errada", proximo).andExpect(status().isUnauthorized());

        // o administrador tira a verificação de quem perdeu o celular
        enviar(c.gestor(), "/api/me/2fa/iniciar", "").andExpect(status().isOk());                // (gestor sem 2FA ativa: nada a remover pelo admin)
        mvc.perform(delete("/api/usuarios/" + c.biaId() + "/2fa").header("Authorization", "Bearer " + c.admin())).andExpect(status().isUnprocessableEntity());
        mvc.perform(delete("/api/usuarios/" + c.anaId() + "/2fa").header("Authorization", "Bearer " + c.ana())).andExpect(status().isForbidden());
        mvc.perform(delete("/api/usuarios/" + c.anaId() + "/2fa").header("Authorization", "Bearer " + c.admin())).andExpect(status().isNoContent());
        login(nome, TestesAuth.SENHA_PROPRIA, null).andExpect(status().isOk());
    }

    @Test
    void auditoriaRegistraEventosESoOAdministradorVe() throws Exception {
        C c = cenario();
        String ana = "Ana " + c.s();
        errar(ana, 5);
        login(ana, "x", null).andExpect(status().isLocked());
        enviar(c.admin(), "/api/usuarios/" + c.anaId() + "/desbloquear", "").andExpect(status().isOk());
        StringBuilder acoes = new StringBuilder();
        for (JsonNode r : corpo(ler(c.admin(), "/api/auditoria").andExpect(status().isOk()))) acoes.append(r.get("acao").asText()).append(' ');
        assertThat(acoes.toString()).contains("USUARIO_CRIADO").contains("LOGIN").contains("CONTA_BLOQUEADA").contains("CONTA_DESBLOQUEADA");
        ler(c.gestor(), "/api/auditoria").andExpect(status().isForbidden());
        ler(c.ana(), "/api/auditoria").andExpect(status().isForbidden());
    }

    @Test
    void chatLimitaMensagensEmPoucoTempo() throws Exception {
        C c = cenario();
        for (int i = 0; i < 20; i++) enviar(c.ana(), "/api/ajuda/minha", "{\"texto\":\"msg " + i + "\"}").andExpect(status().isCreated());
        enviar(c.ana(), "/api/ajuda/minha", "{\"texto\":\"demais\"}").andExpect(status().isUnprocessableEntity());
        enviar(c.bia(), "/api/ajuda/minha", "{\"texto\":\"outra pessoa pode\"}").andExpect(status().isCreated());   // o limite é por pessoa
    }

    @Test
    void segredoDeExemploNaoSobeSemPermissao() {
        String exemplo = "dev-only-secret-change-me-dev-only-secret-change-me";
        assertThatThrownBy(() -> new JwtService(exemplo, 12, false)).hasMessageContaining("JWT_SECRET");
        assertThatThrownBy(() -> new JwtService("troque-este-segredo-em-producao-com-32-bytes-ou-mais", 12, false)).hasMessageContaining("JWT_SECRET");
        new JwtService(exemplo, 12, true);                                                          // desenvolvimento local, com permissão explícita
        new JwtService("um-segredo-de-verdade-com-mais-de-32-bytes-0123456789", 12, false);
    }
}
