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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Servidor de e-mail configurado pelo administrador: permissões, senha criptografada, teste com erro explicado e uso nas automações. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ConfigEmailTest {
    private static final String URL = "/api/configuracao/email";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    record C(String admin, String gestor, String usuario, long tenantId, String sfx) {}

    private JsonNode corpo(ResultActions r) throws Exception { return json.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)); }
    private ResultActions enviar(String t, String url, String b) throws Exception {
        return mvc.perform(post(url).header("Authorization", "Bearer " + t).contentType(MediaType.APPLICATION_JSON).content(b));
    }
    private ResultActions salvar(String t, String b) throws Exception {
        return mvc.perform(put(URL).header("Authorization", "Bearer " + t).contentType(MediaType.APPLICATION_JSON).content(b));
    }
    private JsonNode obter(String t) throws Exception { return corpo(mvc.perform(get(URL).header("Authorization", "Bearer " + t)).andExpect(status().isOk())); }
    private JsonNode consultar(String t, String url) throws Exception { return corpo(mvc.perform(get(url).header("Authorization", "Bearer " + t)).andExpect(status().isOk())); }

    private C cenario() throws Exception {
        String s = UUID.randomUUID().toString().substring(0, 8);
        mvc.perform(post("/api/auth/registrar-empresa").contentType(MediaType.APPLICATION_JSON)
                .content("{\"empresa\":\"E\",\"nome\":\"Adm-" + s + "\",\"email\":\"a-" + s + "@t.com\",\"senha\":\"senha12345\"}")).andExpect(status().isCreated());
        String admin = TestesAuth.entrar(mvc, json, "a-" + s + "@t.com");
        long setor = consultar(admin, "/api/setores").get(0).get("id").asLong();
        enviar(admin, "/api/usuarios", "{\"nome\":\"Ges-" + s + "\",\"senha\":\"senha12345\",\"papel\":\"GESTOR\",\"setorId\":" + setor + "}").andExpect(status().isCreated());
        enviar(admin, "/api/usuarios", "{\"nome\":\"Usu-" + s + "\",\"senha\":\"senha12345\",\"papel\":\"USUARIO\",\"setorId\":" + setor + "}").andExpect(status().isCreated());
        return new C(admin, TestesAuth.entrar(mvc, json, "Ges-" + s), TestesAuth.entrar(mvc, json, "Usu-" + s),
                jdbc.queryForObject("select tenant_id from usuario where id = ?", Long.class, consultar(admin, "/api/me").get("id").asLong()), s);
    }

    private String config(int porta, String usuario, String senha) {
        return "{\"host\":\"127.0.0.1\",\"porta\":" + porta + ",\"seguranca\":\"NENHUMA\",\"usuario\":" + (usuario == null ? "null" : "\"" + usuario + "\"")
                + (senha == null ? "" : ",\"senha\":\"" + senha + "\"") + ",\"remetente\":\"avisos@minhaempresa.com\"}";
    }

    @Test
    void soOAdministradorMexeNaConfiguracao() throws Exception {
        C c = cenario();
        for (String quem : new String[]{c.gestor(), c.usuario()}) {
            mvc.perform(get(URL).header("Authorization", "Bearer " + quem)).andExpect(status().isForbidden());
            salvar(quem, config(2525, null, null)).andExpect(status().isForbidden());
            mvc.perform(delete(URL).header("Authorization", "Bearer " + quem)).andExpect(status().isForbidden());
            enviar(quem, URL + "/teste", "{\"para\":\"x@y.com\"}").andExpect(status().isForbidden());
        }
        mvc.perform(get(URL)).andExpect(status().isUnauthorized());
        assertThat(obter(c.admin()).get("configurado").asBoolean()).isFalse();
    }

    @Test
    void validaOsCampos() throws Exception {
        C c = cenario();
        salvar(c.admin(), "{\"host\":\"\",\"porta\":587,\"seguranca\":\"STARTTLS\",\"remetente\":\"a@b.com\"}").andExpect(status().isUnprocessableEntity());
        salvar(c.admin(), "{\"host\":\"smtp exemplo com\",\"porta\":587,\"seguranca\":\"STARTTLS\",\"remetente\":\"a@b.com\"}").andExpect(status().isUnprocessableEntity());
        salvar(c.admin(), "{\"host\":\"127.0.0.1\",\"porta\":0,\"seguranca\":\"STARTTLS\",\"remetente\":\"a@b.com\"}").andExpect(status().isUnprocessableEntity());
        salvar(c.admin(), "{\"host\":\"127.0.0.1\",\"porta\":99999,\"seguranca\":\"STARTTLS\",\"remetente\":\"a@b.com\"}").andExpect(status().isUnprocessableEntity());
        salvar(c.admin(), "{\"host\":\"127.0.0.1\",\"porta\":587,\"remetente\":\"a@b.com\"}").andExpect(status().isUnprocessableEntity());              // sem segurança
        salvar(c.admin(), "{\"host\":\"127.0.0.1\",\"porta\":587,\"seguranca\":\"STARTTLS\",\"remetente\":\"nao-e-email\"}").andExpect(status().isUnprocessableEntity());
        salvar(c.admin(), config(587, "usuario", null)).andExpect(status().isUnprocessableEntity());                                                      // usuário sem senha
        assertThat(obter(c.admin()).get("configurado").asBoolean()).isFalse();
    }

    @Test
    void senhaFicaCriptografada_enuncaVoltaPelaApi_eEhMantidaQuandoNaoInformada() throws Exception {
        C c = cenario();
        var salvo = corpo(salvar(c.admin(), config(2525, "meu-usuario", "SenhaSecreta#123")).andExpect(status().isOk()));
        assertThat(salvo.get("configurado").asBoolean()).isTrue();
        assertThat(salvo.get("temSenha").asBoolean()).isTrue();
        assertThat(salvo.get("usuario").asText()).isEqualTo("meu-usuario");
        assertThat(salvo.toString()).doesNotContain("SenhaSecreta");
        assertThat(obter(c.admin()).toString()).doesNotContain("SenhaSecreta");

        String guardado = jdbc.queryForObject("select senha_cifrada from tenant_email where tenant_id = ?", String.class, c.tenantId());
        assertThat(guardado).isNotBlank().doesNotContain("SenhaSecreta");                  // no banco só há o texto criptografado

        // salvar de novo sem senha mantém a atual; com senha vazia apaga (e então exige informar, pois ainda há usuário)
        salvar(c.admin(), config(2525, "meu-usuario", null)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select senha_cifrada from tenant_email where tenant_id = ?", String.class, c.tenantId())).isEqualTo(guardado);
        salvar(c.admin(), config(2525, "meu-usuario", "")).andExpect(status().isUnprocessableEntity());
        // sem usuário não há autenticação: a senha some
        salvar(c.admin(), config(2525, null, null)).andExpect(status().isOk());
        assertThat(obter(c.admin()).get("temSenha").asBoolean()).isFalse();
    }

    @Test
    void testeEnviaPeloServidorConfigurado_eExplicaOsErros() throws Exception {
        C c = cenario();
        try (SmtpFalso smtp = new SmtpFalso("conta@empresa.com", "senha-certa")) {
            // senha errada: o erro diz o que fazer
            salvar(c.admin(), config(smtp.porta(), "conta@empresa.com", "senha-errada")).andExpect(status().isOk());
            var erro = corpo(enviar(c.admin(), URL + "/teste", "{\"para\":\"eu@exemplo.com\"}").andExpect(status().isOk()));
            assertThat(erro.get("ok").asBoolean()).isFalse();
            assertThat(erro.get("mensagem").asText()).contains("recusou o usuário ou a senha").contains("senha de aplicativo");
            assertThat(smtp.mensagens).isEmpty();

            // senha certa: chega, do remetente configurado, para o destino pedido
            salvar(c.admin(), config(smtp.porta(), "conta@empresa.com", "senha-certa")).andExpect(status().isOk());
            var ok = corpo(enviar(c.admin(), URL + "/teste", "{\"para\":\"eu@exemplo.com\"}").andExpect(status().isOk()));
            assertThat(ok.get("ok").asBoolean()).isTrue();
            assertThat(ok.get("mensagem").asText()).contains("eu@exemplo.com").contains("127.0.0.1:" + smtp.porta());
            assertThat(smtp.mensagens).hasSize(1);
            assertThat(smtp.mensagens.get(0).de()).isEqualTo("avisos@minhaempresa.com");
            assertThat(smtp.mensagens.get(0).para()).containsExactly("eu@exemplo.com");
            assertThat(smtp.mensagens.get(0).bruto()).contains("Subject: Teste de e-mail do Viriato");
            assertThat(smtp.tentativasDeLogin).contains("conta@empresa.com:senha-certa");   // a senha foi decifrada e usada

            // destino inválido
            enviar(c.admin(), URL + "/teste", "{\"para\":\"sem-arroba\"}").andExpect(status().isBadRequest());
        }
        // porta onde ninguém escuta
        salvar(c.admin(), config(9, null, null)).andExpect(status().isOk());
        var recusado = corpo(enviar(c.admin(), URL + "/teste", "{\"para\":\"eu@exemplo.com\"}").andExpect(status().isOk()));
        assertThat(recusado.get("ok").asBoolean()).isFalse();
        assertThat(recusado.get("mensagem").asText()).contains("Não foi possível conectar a 127.0.0.1:9");
    }

    @Test
    void semConfiguracaoVoltaAoPadraoDoSistema() throws Exception {
        C c = cenario();
        try (SmtpFalso smtp = new SmtpFalso(null, null)) {
            salvar(c.admin(), config(smtp.porta(), null, null)).andExpect(status().isOk());
            mvc.perform(delete(URL).header("Authorization", "Bearer " + c.admin())).andExpect(status().isNoContent());
            assertThat(obter(c.admin()).get("configurado").asBoolean()).isFalse();
            // no ambiente de teste o envio padrão está desligado: o teste diz isso, e nada sai pelo servidor da empresa removido
            var r = corpo(enviar(c.admin(), URL + "/teste", "{\"para\":\"eu@exemplo.com\"}").andExpect(status().isOk()));
            assertThat(r.get("ok").asBoolean()).isFalse();
            assertThat(r.get("mensagem").asText()).contains("desligado");
            assertThat(smtp.mensagens).isEmpty();
        }
    }

    @Test
    void cadaEmpresaTemOSeuServidor() throws Exception {
        C a = cenario();
        C b = cenario();
        salvar(a.admin(), config(2525, null, null)).andExpect(status().isOk());
        assertThat(obter(a.admin()).get("configurado").asBoolean()).isTrue();
        assertThat(obter(b.admin()).get("configurado").asBoolean()).isFalse();   // a outra empresa não vê nem herda
    }

    @Test
    void asAutomacoesEnviamPeloServidorDaEmpresa() throws Exception {
        C c = cenario();
        try (SmtpFalso smtp = new SmtpFalso("conta@empresa.com", "senha-certa")) {
            salvar(c.admin(), config(smtp.porta(), "conta@empresa.com", "senha-certa")).andExpect(status().isOk());
            JsonNode setor = consultar(c.admin(), "/api/setores").get(0);
            long cliente = corpo(enviar(c.admin(), "/api/clientes", "{\"numero\":\"5\",\"nome\":\"Cliente Final\",\"email\":\"cliente@final.com\"}").andExpect(status().isCreated())).get("id").asLong();
            JsonNode fluxo = consultar(c.admin(), "/api/fluxos").get(0);
            long analise = fluxo.get("etapas").get(1).get("id").asLong();
            enviar(c.admin(), "/api/fluxos/" + fluxo.get("id").asLong() + "/automacoes", "{\"evento\":\"ENTRAR_ETAPA\",\"etapaId\":" + analise
                    + ",\"tipo\":\"EMAIL_EMPRESA\",\"assunto\":\"Processo {{numero}}\",\"mensagem\":\"Olá {{empresa}}\"}").andExpect(status().isCreated());
            long u = consultar(c.admin(), "/api/usuarios").get(2).get("id").asLong();
            long p = corpo(enviar(c.admin(), "/api/processos", "{\"clienteId\":" + cliente + ",\"entregaId\":" + setor.get("entregas").get(0).get("id").asLong()
                    + ",\"setorId\":" + setor.get("id").asLong() + ",\"responsavelId\":" + consultar(c.admin(), "/api/me").get("id").asLong() + "}").andExpect(status().isCreated())).get("id").asLong();
            enviar(c.admin(), "/api/processos/" + p + "/avancar", "{\"etapaId\":" + analise + "}").andExpect(status().isOk());

            assertThat(smtp.mensagens).hasSize(1);
            assertThat(smtp.mensagens.get(0).para()).containsExactly("cliente@final.com");
            assertThat(smtp.mensagens.get(0).de()).isEqualTo("avisos@minhaempresa.com");
            JsonNode log = consultar(c.admin(), "/api/processos/" + p + "/automacoes").get(0);
            assertThat(log.get("status").asText()).isEqualTo("OK");
            assertThat(log.get("mensagem").asText()).contains("cliente@final.com").contains("127.0.0.1:" + smtp.porta());
            assertThat(u).isPositive();
        }
    }
}
