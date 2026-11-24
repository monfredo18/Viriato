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

/** LGPD: exportar e anonimizar o titular, com isolamento entre empresas e só para administrador. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LgpdTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private JsonNode corpo(ResultActions r) throws Exception { return json.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)); }
    private ResultActions enviar(String t, String url, String b) throws Exception {
        return mvc.perform(post(url).header("Authorization", "Bearer " + t).contentType(MediaType.APPLICATION_JSON).content(b));
    }
    private ResultActions ler(String t, String url) throws Exception { return mvc.perform(get(url).header("Authorization", "Bearer " + t)); }

    record C(String admin, String gestor, String ana, long anaId, String nomeAna, String s, long processo, long clienteId) {}

    private String novaEmpresa(String s) throws Exception {
        return corpo(mvc.perform(post("/api/auth/registrar-empresa").contentType(MediaType.APPLICATION_JSON)
                .content("{\"empresa\":\"E" + s + "\",\"nome\":\"Adm " + s + "\",\"email\":\"a-" + s + "@t.com\",\"senha\":\"senha12345\"}")).andExpect(status().isCreated())).get("token").asText();
    }

    private long criar(String admin, String nome, String email, String papel, long setor) throws Exception {
        return corpo(enviar(admin, "/api/usuarios", "{\"nome\":\"" + nome + "\",\"email\":\"" + email + "\",\"senha\":\"senha12345\",\"papel\":\"" + papel + "\",\"setorId\":" + setor + "}").andExpect(status().isCreated())).get("id").asLong();
    }

    private C cenario() throws Exception {
        String s = UUID.randomUUID().toString().substring(0, 6);
        String admin = novaEmpresa(s);
        JsonNode geral = corpo(ler(admin, "/api/setores")).get(0);
        long setor = geral.get("id").asLong(), entrega = geral.get("entregas").get(0).get("id").asLong();
        String nomeAna = "Ana Paula " + s;
        long anaId = criar(admin, nomeAna, "ana-" + s + "@t.com", "USUARIO", setor);
        criar(admin, "Gestor " + s, "g-" + s + "@t.com", "GESTOR", setor);
        String ana = TestesAuth.entrar(mvc, json, nomeAna), gestor = TestesAuth.entrar(mvc, json, "Gestor " + s);
        long cli = corpo(enviar(admin, "/api/clientes", "{\"numero\":\"7" + Math.abs(s.hashCode() % 1000) + "\",\"nome\":\"Construtora " + s + "\"}").andExpect(status().isCreated())).get("id").asLong();
        long p = corpo(enviar(ana, "/api/processos", "{\"clienteId\":" + cli + ",\"entregaId\":" + entrega + "}").andExpect(status().isCreated())).get("id").asLong();
        enviar(ana, "/api/processos/" + p + "/comentarios", "{\"texto\":\"Conferi os documentos\"}").andExpect(status().is2xxSuccessful());
        return new C(admin, gestor, ana, anaId, nomeAna, s, p, cli);
    }

    @Test
    void exportacaoTrazOsDadosDaPessoaESoOAdministradorVe() throws Exception {
        C c = cenario();
        JsonNode e = corpo(ler(c.admin(), "/api/lgpd/usuarios/" + c.anaId() + "/exportacao").andExpect(status().isOk()));
        assertThat(e.get("cadastro").get("email").asText()).isEqualTo("ana-" + c.s() + "@t.com");
        assertThat(e.get("cadastro").get("nome").asText()).isEqualTo(c.nomeAna());
        assertThat(e.get("processosSobResponsabilidade")).hasSize(1);
        assertThat(e.get("atividadesNosProcessos").toString()).contains("Conferi os documentos");
        assertThat(e.get("acessosERegistrosDeAuditoria").toString()).contains("LOGIN");
        ler(c.gestor(), "/api/lgpd/usuarios/" + c.anaId() + "/exportacao").andExpect(status().isForbidden());
        ler(c.ana(), "/api/lgpd/usuarios/" + c.anaId() + "/exportacao").andExpect(status().isForbidden());
        enviar(c.gestor(), "/api/lgpd/usuarios/" + c.anaId() + "/anonimizar", "{}").andExpect(status().isForbidden());
        byte[] pdf = ler(c.admin(), "/api/lgpd/usuarios/" + c.anaId() + "/exportacao.pdf").andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
        // a exportação fica registrada
        assertThat(corpo(ler(c.admin(), "/api/auditoria")).toString()).contains("LGPD_EXPORTACAO");
    }

    @Test
    void outraEmpresaNaoEnxergaOTitular() throws Exception {
        C c = cenario();
        String outro = novaEmpresa(UUID.randomUUID().toString().substring(0, 6));
        ler(outro, "/api/lgpd/usuarios/" + c.anaId() + "/exportacao").andExpect(status().isNotFound());
        enviar(outro, "/api/lgpd/usuarios/" + c.anaId() + "/anonimizar", "{}").andExpect(status().isNotFound());
        ler(outro, "/api/lgpd/clientes/" + c.clienteId() + "/exportacao").andExpect(status().isNotFound());
    }

    @Test
    void anonimizarApagaOPessoalEMantemOHistorico() throws Exception {
        C c = cenario();
        enviar(c.admin(), "/api/lgpd/usuarios/" + c.anaId() + "/anonimizar", "{}").andExpect(status().isNoContent());

        // não entra mais, nem com a senha certa, e o token antigo morreu
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"nome\":\"" + c.nomeAna() + "\",\"senha\":\"" + TestesAuth.SENHA_PROPRIA + "\"}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"nome\":\"Anonimizado " + c.anaId() + "\",\"senha\":\"" + TestesAuth.SENHA_PROPRIA + "\"}")).andExpect(status().isUnauthorized());
        ler(c.ana(), "/api/me").andExpect(status().isUnauthorized());

        // o cadastro ficou neutro
        JsonNode e = corpo(ler(c.admin(), "/api/lgpd/usuarios/" + c.anaId() + "/exportacao").andExpect(status().isOk()));
        assertThat(e.get("cadastro").get("nome").asText()).isEqualTo("Anonimizado " + c.anaId());
        assertThat(e.get("cadastro").get("email").asText()).endsWith("@anonimizado.invalid");
        assertThat(e.get("cadastro").get("ativo").asBoolean()).isFalse();
        assertThat(e.get("cadastro").get("anonimizadoEm").isNull()).isFalse();
        // acessos: origem apagada, evento mantido
        for (JsonNode a : e.get("acessosERegistrosDeAuditoria")) { assertThat(a.get("ip").isNull()).isTrue(); assertThat(a.get("navegador").isNull()).isTrue(); }

        // o nome antigo não aparece mais em lugar nenhum que o sistema mostra
        assertThat(corpo(ler(c.admin(), "/api/usuarios")).toString()).doesNotContain(c.nomeAna());
        JsonNode detalhe = corpo(ler(c.admin(), "/api/processos/" + c.processo()).andExpect(status().isOk()));
        assertThat(detalhe.toString()).doesNotContain(c.nomeAna()).contains("Conferi os documentos");   // histórico fica, com o nome neutro
        assertThat(corpo(ler(c.admin(), "/api/auditoria")).toString()).doesNotContain(c.nomeAna()).contains("LGPD_ANONIMIZACAO");

        // regras
        enviar(c.admin(), "/api/lgpd/usuarios/" + c.anaId() + "/anonimizar", "{}").andExpect(status().isUnprocessableEntity());   // não se repete
        long eu = corpo(ler(c.admin(), "/api/me")).get("id").asLong();
        enviar(c.admin(), "/api/lgpd/usuarios/" + eu + "/anonimizar", "{}").andExpect(status().isUnprocessableEntity());          // nem a si mesmo
        enviar(c.admin(), "/api/lgpd/usuarios/999999/anonimizar", "{}").andExpect(status().isNotFound());
    }

    @Test
    void empresaAtendidaPodeSerExportadaEAnonimizada() throws Exception {
        C c = cenario();
        JsonNode e = corpo(ler(c.admin(), "/api/lgpd/clientes/" + c.clienteId() + "/exportacao").andExpect(status().isOk()));
        assertThat(e.get("cadastro").get("nome").asText()).isEqualTo("Construtora " + c.s());
        assertThat(e.get("processos")).hasSize(1);
        assertThat(ler(c.admin(), "/api/lgpd/clientes/" + c.clienteId() + "/exportacao.pdf").andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).isNotEmpty();
        enviar(c.admin(), "/api/lgpd/clientes/" + c.clienteId() + "/anonimizar", "{}").andExpect(status().isNoContent());
        assertThat(corpo(ler(c.admin(), "/api/processos/" + c.processo())).toString()).doesNotContain("Construtora " + c.s());
        enviar(c.admin(), "/api/lgpd/clientes/" + c.clienteId() + "/anonimizar", "{}").andExpect(status().isUnprocessableEntity());
    }
}
