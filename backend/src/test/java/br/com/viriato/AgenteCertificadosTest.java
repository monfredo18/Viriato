package br.com.viriato;

import static br.com.viriato.Crm.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.viriato.agente.AgenteRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** Certificados lidos do computador pelo agente local: pareamento, sincronização, reconhecimento pelo CNPJ, vínculo e segurança das rotas. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(Crm.StorageFake.class)
class AgenteCertificadosTest {
    static final String CNPJ = "11222333000181";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AgenteRepository agentes;

    /** Sem credencial válida o Spring responde 401 ou 403, conforme a rota. */
    private static org.springframework.test.web.servlet.ResultMatcher recusado() {
        return r -> assertThat(r.getResponse().getStatus()).isIn(401, 403);
    }

    private JsonNode certificados(String t) throws Exception { return corpo(json, ler(mvc, t, "/api/certificados").andExpect(status().isOk())); }

    private JsonNode porTitular(String t, String titular) throws Exception {
        for (JsonNode c : certificados(t)) if (c.get("titular").asText().contains(titular)) return c;
        throw new AssertionError("certificado não encontrado: " + titular);
    }

    @Test
    void naoHaMaisRotaParaEnviarOuRegistrarCertificado() throws Exception {
        Empresa e = empresa(mvc, json);
        long cli = cliente(mvc, json, e.gestor(), "50", "Sem upload " + e.s(), null);
        mvc.perform(multipart("/api/clientes/" + cli + "/certificados/a1").file(new org.springframework.mock.web.MockMultipartFile("arquivo", "c.pfx", "application/x-pkcs12", new byte[]{1}))
                .param("senha", "x").header("Authorization", "Bearer " + e.gestor())).andExpect(status().is4xxClientError());
        assertThat(enviar(mvc, e.gestor(), "/api/clientes/" + cli + "/certificados/a3", "{\"titular\":\"X\",\"validoAte\":\"2030-01-01\"}").andReturn().getResponse().getStatus()).isIn(404, 405);
        assertThat(corpo(json, ler(mvc, e.gestor(), "/api/clientes/" + cli + "/ficha")).get("certificados")).isEmpty();
    }

    @Test
    void codigoDeUsoUnicoEOTokenSoApareceUmaVez() throws Exception {
        Empresa e = empresa(mvc, json);
        JsonNode p = corpo(json, enviar(mvc, e.usuario(), "/api/agentes/codigo", "{}").andExpect(status().isCreated()));   // qualquer usuário liga o próprio computador
        String codigo = p.get("codigo").asText();
        assertThat(codigo).matches("[A-Z2-9]{4}-[A-Z2-9]{4}");
        String corpoParear = "{\"codigo\":\"" + codigo.toLowerCase().replace("-", " ") + "\",\"nome\":\"PC da recepção\",\"versao\":\"1.2\"}";   // digitar em minúsculas, com espaço, funciona
        JsonNode r = corpo(json, mvc.perform(post("/api/auth/agente/parear").contentType(MediaType.APPLICATION_JSON).content(corpoParear)).andExpect(status().isOk()));
        assertThat(r.get("token").asText()).startsWith("vagt_");
        // o mesmo código não vale duas vezes, nem um código inventado
        mvc.perform(post("/api/auth/agente/parear").contentType(MediaType.APPLICATION_JSON).content(corpoParear)).andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/auth/agente/parear").contentType(MediaType.APPLICATION_JSON).content("{\"codigo\":\"AAAA-BBBB\",\"nome\":\"x\"}")).andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/auth/agente/parear").contentType(MediaType.APPLICATION_JSON).content("{\"nome\":\"x\"}")).andExpect(status().isUnprocessableEntity());

        // a lista não traz token nem hash; o usuário vê só o próprio agente, a gestão vê todos
        JsonNode meus = corpo(json, ler(mvc, e.usuario(), "/api/agentes").andExpect(status().isOk()));
        assertThat(meus).hasSize(1);
        assertThat(meus.get(0).get("nome").asText()).isEqualTo("PC da recepção");
        assertThat(meus.toString()).doesNotContain("vagt_").doesNotContainIgnoringCase("hash").doesNotContainIgnoringCase("token");
        String doGestor = Agentes.parear(mvc, json, e.gestor(), "PC do gestor");
        assertThat(corpo(json, ler(mvc, e.usuario(), "/api/agentes"))).hasSize(1);
        assertThat(corpo(json, ler(mvc, e.gestor(), "/api/agentes"))).hasSize(2);
        assertThat(doGestor).startsWith("vagt_");

        // o código vencido não serve
        String vencido = corpo(json, enviar(mvc, e.usuario(), "/api/agentes/codigo", "{}")).get("codigo").asText();
        jdbcExpira(vencido);
        mvc.perform(post("/api/auth/agente/parear").contentType(MediaType.APPLICATION_JSON).content("{\"codigo\":\"" + vencido + "\",\"nome\":\"x\"}")).andExpect(status().isUnprocessableEntity());
    }

    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    private static String sha256(String s) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private void jdbcExpira(String codigo) {
        jdbc.update("update agente_pareamento set expira_em = ? where codigo_hash = ?", java.sql.Timestamp.from(Instant.now().minusSeconds(5)),
                sha256(codigo.replace("-", "")));
    }

    @Test
    void certificadoLidoEReconhecidoPeloCnpjELigadoAEmpresa() throws Exception {
        Empresa e = empresa(mvc, json);
        long cli = cliente(mvc, json, e.gestor(), "51", "Alfa " + e.s(), "{\"cnpj\":\"" + CNPJ + "\"}");
        long outra = cliente(mvc, json, e.gestor(), "52", "Beta " + e.s(), "{\"cnpj\":\"45723174000110\"}");
        String ag = Agentes.parear(mvc, json, e.usuario(), "PC do usuário");   // o computador é de um usuário comum; a empresa é da gestão
        String derAlfa = Agentes.der("ALFA CONSTRUTORA LTDA:" + CNPJ + " " + e.s(), 365), derContador = Agentes.der("CONTADOR JOAO " + e.s(), 100);
        Agentes.contato(mvc, ag, List.of(derAlfa, derContador)).andExpect(status().isOk());

        JsonNode alfa = porTitular(e.gestor(), "ALFA CONSTRUTORA");
        assertThat(alfa.get("cnpjCertificado").asText()).isEqualTo(CNPJ);
        assertThat(alfa.get("clienteId").asLong()).isEqualTo(cli);                 // ligado sozinho pelo CNPJ do titular
        assertThat(alfa.get("clienteNome").asText()).isEqualTo("Alfa " + e.s());
        assertThat(alfa.get("emissora").asText()).contains("ICP-Brasil");
        assertThat(alfa.get("numeroSerie").asText()).isNotBlank();
        assertThat(alfa.get("situacao").asText()).isEqualTo("VALIDO");
        assertThat(alfa.get("diasParaVencer").asLong()).isBetween(360L, 366L);
        assertThat(alfa.get("presente").asBoolean()).isTrue();
        assertThat(alfa.get("computadores").get(0).asText()).isEqualTo("PC do usuário");
        assertThat(alfa.get("computadoresConectados").asInt()).isEqualTo(1);
        assertThat(alfa.toString()).doesNotContainIgnoringCase("thumbprint").doesNotContainIgnoringCase("senha").doesNotContainIgnoringCase("chave");

        JsonNode contador = porTitular(e.gestor(), "CONTADOR JOAO");               // sem CNPJ: fica sem empresa até alguém escolher
        assertThat(contador.get("clienteId").isNull()).isTrue();
        JsonNode ficha = corpo(json, ler(mvc, e.gestor(), "/api/clientes/" + cli + "/ficha")).get("certificados");
        assertThat(ficha).hasSize(1);
        assertThat(ficha.get(0).get("titular").asText()).contains("ALFA CONSTRUTORA");
        assertThat(corpo(json, ler(mvc, e.gestor(), "/api/clientes/" + outra + "/ficha")).get("certificados")).isEmpty();
        assertThat(corpo(json, ler(mvc, e.admin(), "/api/auditoria")).toString()).contains("CERTIFICADOS_DETECTADOS").contains("PC do usuário");

        // a gestão liga o do contador à empresa; o agente não desfaz mais essa escolha nas próximas leituras
        long idContador = contador.get("id").asLong();
        JsonNode lig = corpo(json, alterar(mvc, e.gestor(), "/api/certificados/" + idContador, "{\"clienteId\":" + outra + "}").andExpect(status().isOk()));
        assertThat(lig.get("clienteId").asLong()).isEqualTo(outra);
        assertThat(lig.get("vinculoManual").asBoolean()).isTrue();
        Agentes.contato(mvc, ag, List.of(derAlfa, derContador)).andExpect(status().isOk());
        assertThat(certificados(e.gestor())).hasSize(2);                          // os mesmos, reconhecidos: nada duplicou
        // o do contador segue ligado à Beta
        assertThat(porTitular(e.gestor(), "CONTADOR JOAO").get("clienteId").asLong()).isEqualTo(outra);
        alterar(mvc, e.gestor(), "/api/certificados/" + idContador, "{\"clienteId\":null}").andExpect(status().isOk());
        assertThat(porTitular(e.gestor(), "CONTADOR JOAO").get("clienteId").isNull()).isTrue();
        assertThat(corpo(json, ler(mvc, e.admin(), "/api/auditoria")).toString()).contains("CERTIFICADO_VINCULADO");
    }

    @Test
    void cnpjCompletoDecideEntreMatrizEFilial() throws Exception {
        Empresa e = empresa(mvc, json);
        cliente(mvc, json, e.gestor(), "53", "Matriz " + e.s(), "{\"cnpj\":\"11222333000181\"}");
        cliente(mvc, json, e.gestor(), "54", "Filial " + e.s(), "{\"cnpj\":\"11222333000262\"}");
        String ag = Agentes.parear(mvc, json, e.gestor(), "PC");
        // matriz e filial têm a mesma raiz; o CNPJ completo do titular decide
        Agentes.contato(mvc, ag, List.of(Agentes.der("MATRIZ SA:11222333000181 " + e.s(), 200))).andExpect(status().isOk());
        assertThat(porTitular(e.gestor(), "MATRIZ SA").get("clienteNome").asText()).isEqualTo("Matriz " + e.s());
    }

    @Test
    void oQueSaiDoComputadorFicaAusenteESemAlertaEVoltaQuandoReaparece() throws Exception {
        Empresa e = empresa(mvc, json);
        String ag = Agentes.parear(mvc, json, e.gestor(), "PC");
        String der = Agentes.der("EFEMERO " + e.s(), 200);
        Agentes.contato(mvc, ag, List.of(der)).andExpect(status().isOk());
        assertThat(porTitular(e.gestor(), "EFEMERO").get("presente").asBoolean()).isTrue();
        Agentes.contato(mvc, ag, List.of()).andExpect(status().isOk());             // saiu do repositório do Windows
        JsonNode c = porTitular(e.gestor(), "EFEMERO");
        assertThat(c.get("presente").asBoolean()).isFalse();
        assertThat(c.get("computadores")).isEmpty();
        Agentes.contato(mvc, ag, List.of(der)).andExpect(status().isOk());           // o mesmo certificado volta: é reconhecido, não duplica
        assertThat(porTitular(e.gestor(), "EFEMERO").get("presente").asBoolean()).isTrue();
        assertThat(certificados(e.gestor())).hasSize(1);
        // lixo no meio da lista é ignorado, sem derrubar o resto
        Agentes.contato(mvc, ag, "{\"certificados\":[\"%%%\",\"" + der + "\",\"QUJD\"]}").andExpect(status().isOk());
        assertThat(certificados(e.gestor())).hasSize(1);
        // contato sem lista (só batimento) não mexe nos certificados
        Agentes.contato(mvc, ag, "{\"versao\":\"2.0\"}").andExpect(status().isOk());
        assertThat(porTitular(e.gestor(), "EFEMERO").get("presente").asBoolean()).isTrue();
    }

    @Test
    void doisComputadoresComOMesmoCertificadoEDesligarUmNaoApagaOOutro() throws Exception {
        Empresa e = empresa(mvc, json);
        String a1 = Agentes.parear(mvc, json, e.gestor(), "PC A");
        String a2 = Agentes.parear(mvc, json, e.usuario(), "PC B");
        String der = Agentes.der("COMPARTILHADO " + e.s(), 200);
        Agentes.contato(mvc, a1, List.of(der)).andExpect(status().isOk());
        Agentes.contato(mvc, a2, List.of(der)).andExpect(status().isOk());
        JsonNode c = porTitular(e.gestor(), "COMPARTILHADO");
        assertThat(certificados(e.gestor())).hasSize(1);
        assertThat(c.get("computadores")).hasSize(2);

        long idAgenteA = corpo(json, ler(mvc, e.gestor(), "/api/agentes")).get(0).get("id").asLong();
        long idDoA = 0;
        for (JsonNode a : corpo(json, ler(mvc, e.gestor(), "/api/agentes"))) if (a.get("nome").asText().equals("PC A")) idDoA = a.get("id").asLong();
        assertThat(idAgenteA).isPositive();
        mvc.perform(delete("/api/agentes/" + idDoA).header("Authorization", "Bearer " + e.usuario())).andExpect(status().isNotFound());   // usuário não desliga o de outra pessoa
        mvc.perform(delete("/api/agentes/" + idDoA).header("Authorization", "Bearer " + e.gestor())).andExpect(status().isNoContent());
        c = porTitular(e.gestor(), "COMPARTILHADO");
        assertThat(c.get("presente").asBoolean()).isTrue();                          // continua no PC B
        assertThat(c.get("computadores")).hasSize(1);
        Agentes.contato(mvc, a1, "{}").andExpect(recusado());   // o token do desligado não vale mais

        long idDoB = 0;
        for (JsonNode a : corpo(json, ler(mvc, e.usuario(), "/api/agentes"))) idDoB = a.get("id").asLong();
        mvc.perform(delete("/api/agentes/" + idDoB).header("Authorization", "Bearer " + e.usuario())).andExpect(status().isNoContent());   // o dono desliga o próprio
        assertThat(porTitular(e.gestor(), "COMPARTILHADO").get("presente").asBoolean()).isFalse();
        assertThat(corpo(json, ler(mvc, e.admin(), "/api/auditoria")).toString()).contains("AGENTE_PAREADO").contains("AGENTE_REVOGADO");
    }

    @Test
    void permissoesIsolamentoEAsRotasDoAgenteSoAceitamOTokenDoAgente() throws Exception {
        Empresa e = empresa(mvc, json);
        String ag = Agentes.parear(mvc, json, e.gestor(), "PC");
        Agentes.contato(mvc, ag, List.of(Agentes.der("PRIVADO " + e.s(), 200))).andExpect(status().isOk());
        long id = porTitular(e.gestor(), "PRIVADO").get("id").asLong();
        long cli = cliente(mvc, json, e.gestor(), "55", "Priv " + e.s(), null);

        ler(mvc, e.usuario(), "/api/certificados").andExpect(status().isForbidden());                                   // usuário comum não vê o cofre
        alterar(mvc, e.usuario(), "/api/certificados/" + id, "{\"clienteId\":" + cli + "}").andExpect(status().isForbidden());
        Empresa outra = empresa(mvc, json);
        assertThat(certificados(outra.admin())).isEmpty();                                                              // cada empresa vê o seu
        alterar(mvc, outra.admin(), "/api/certificados/" + id, "{\"clienteId\":null}").andExpect(status().isNotFound());
        long clienteDeOutra = cliente(mvc, json, outra.gestor(), "56", "Alheia " + outra.s(), null);
        alterar(mvc, e.gestor(), "/api/certificados/" + id, "{\"clienteId\":" + clienteDeOutra + "}").andExpect(status().isNotFound());   // nem ligar a empresa de outro cliente
        ler(mvc, e.gestor(), "/api/agentes").andExpect(status().isOk());
        assertThat(corpo(json, ler(mvc, outra.admin(), "/api/agentes"))).isEmpty();

        // a pessoa logada não fala como agente, e o agente não fala como pessoa
        mvc.perform(post("/api/agente/contato").header("Authorization", "Bearer " + e.admin()).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(recusado());
        mvc.perform(post("/api/agente/contato").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(recusado());
        mvc.perform(post("/api/agente/contato").header("Authorization", "Bearer vagt_inventado").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(recusado());
        mvc.perform(get("/api/clientes").header("Authorization", "Bearer " + ag)).andExpect(recusado());
        mvc.perform(get("/api/certificados").header("Authorization", "Bearer " + ag)).andExpect(recusado());

        // o agente de outro tenant não alcança a tarefa de quem não é dele
        String agOutra = Agentes.parear(mvc, json, outra.gestor(), "PC outra");
        mvc.perform(post("/api/agente/tarefas/1/resultado").header("Authorization", "Bearer " + agOutra).contentType(MediaType.APPLICATION_JSON).content("{\"httpStatus\":200,\"corpo\":\"\"}")).andExpect(status().isNotFound());
    }

    @Autowired br.com.viriato.usuario.UsuarioRepository usuarios;

    @Test
    void quemSaiDaEmpresaLevaOAgenteJunto() throws Exception {
        Empresa e = empresa(mvc, json);
        String ag = Agentes.parear(mvc, json, e.usuario(), "PC do usuário");
        Agentes.contato(mvc, ag, "{}").andExpect(status().isOk());
        long idUsuario = corpo(json, ler(mvc, e.usuario(), "/api/me")).get("id").asLong();
        alterar(mvc, e.admin(), "/api/usuarios/" + idUsuario, "{\"ativo\":false}").andExpect(status().isOk());
        Agentes.contato(mvc, ag, "{}").andExpect(recusado());
    }

    @Test
    void empresaApagadaSoltaOCertificadoQueContinuaNoComputador() throws Exception {
        Empresa e = empresa(mvc, json);
        long cli = cliente(mvc, json, e.gestor(), "57", "Apagar " + e.s(), "{\"cnpj\":\"" + CNPJ + "\"}");
        String ag = Agentes.parear(mvc, json, e.gestor(), "PC");
        Agentes.contato(mvc, ag, List.of(Agentes.der("APAGAR SA:" + CNPJ + " " + e.s(), 200))).andExpect(status().isOk());
        assertThat(porTitular(e.gestor(), "APAGAR SA").get("clienteId").asLong()).isEqualTo(cli);
        mvc.perform(delete("/api/clientes/" + cli).header("Authorization", "Bearer " + e.gestor())).andExpect(status().isNoContent());
        JsonNode c = porTitular(e.gestor(), "APAGAR SA");
        assertThat(c.get("clienteId").isNull()).isTrue();                 // o certificado segue no computador, só sem empresa
        assertThat(c.get("presente").asBoolean()).isTrue();
    }
}
