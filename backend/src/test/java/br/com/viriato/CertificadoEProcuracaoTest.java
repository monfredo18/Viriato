package br.com.viriato;

import static br.com.viriato.Crm.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.viriato.crm.CertificadoService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Cofre de certificados (A1 com .pfx de verdade, A3 informativo) e procurações com comprovante. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(Crm.StorageFake.class)
class CertificadoEProcuracaoTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired CertificadoService servico;

    private ResultActions a1(String t, long cli, String nome, byte[] bytes, String senha) throws Exception {
        return mvc.perform(multipart("/api/clientes/" + cli + "/certificados/a1").file(new MockMultipartFile("arquivo", nome, "application/x-pkcs12", bytes)).param("senha", senha)
                .header("Authorization", "Bearer " + t));
    }

    @Test
    void a1ValidaASenhaExtraiOsDadosECifraTudo() throws Exception {
        Empresa e = empresa(mvc, json);
        long cli = cliente(mvc, json, e.gestor(), "20", "Cert " + e.s(), "{\"cnpj\":\"11222333000181\"}");
        byte[] pfx = pfx("ALFA CONSTRUTORA LTDA:11222333000181", "senha-do-pfx", 365);

        JsonNode c = corpo(json, a1(e.gestor(), cli, "alfa.pfx", pfx, "senha-do-pfx").andExpect(status().isCreated()));
        assertThat(c.get("tipo").asText()).isEqualTo("A1");
        assertThat(c.get("titular").asText()).contains("ALFA CONSTRUTORA LTDA");
        assertThat(c.get("cnpjCertificado").asText()).isEqualTo("11222333000181");
        assertThat(c.get("emissora").asText()).contains("ICP-Brasil");
        assertThat(c.get("numeroSerie").asText()).isNotBlank();
        assertThat(c.get("situacao").asText()).isEqualTo("VALIDO");
        assertThat(c.get("diasParaVencer").asLong()).isBetween(360L, 366L);
        // nunca expõe o arquivo nem a senha
        String bruto = c.toString() + corpo(json, ler(mvc, e.gestor(), "/api/clientes/" + cli + "/ficha")).toString();
        assertThat(bruto).doesNotContain("senha-do-pfx").doesNotContainIgnoringCase("senhaCifrada").doesNotContainIgnoringCase("arquivoChave");

        // o arquivo guardado está cifrado: não é um PKCS12 legível e não contém o texto do certificado
        byte[] guardado = MEMORIA.entrySet().stream().filter(x -> x.getKey().contains("cliente-" + cli + "/certificados/")).findFirst().orElseThrow().getValue();
        assertThat(guardado).isNotEqualTo(pfx);
        assertThat(new String(guardado, StandardCharsets.ISO_8859_1)).doesNotContain("ALFA CONSTRUTORA").doesNotContain("empresa");
        assertThat(corpo(json, ler(mvc, e.admin(), "/api/auditoria")).toString()).contains("CERTIFICADO_CADASTRADO");
    }

    @Test
    void recusaSenhaErradaArquivoInvalidoVencidoCnpjDeOutraEmpresaEPermissao() throws Exception {
        Empresa e = empresa(mvc, json);
        long cli = cliente(mvc, json, e.gestor(), "21", "Cert2 " + e.s(), "{\"cnpj\":\"11222333000181\"}");
        byte[] pfx = pfx("ALFA LTDA:11222333000181", "senha-certa", 365);
        assertThat(corpo(json, a1(e.gestor(), cli, "a.pfx", pfx, "errada").andExpect(status().isUnprocessableEntity())).get("erro").asText()).contains("senha do certificado está incorreta");
        a1(e.gestor(), cli, "a.pfx", "%PDF-1.4 nao e pfx".getBytes(), "x").andExpect(status().isUnprocessableEntity());                      // conteúdo não confere com a extensão
        a1(e.gestor(), cli, "a.pfx", new byte[]{0x30, (byte) 0x82, 1, 2, 3, 4, 5, 6}, "x").andExpect(status().isUnprocessableEntity());     // parece DER, mas não é PKCS12
        a1(e.gestor(), cli, "a.pdf", pfx, "senha-certa").andExpect(status().isUnprocessableEntity());                                         // extensão errada
        assertThat(corpo(json, a1(e.gestor(), cli, "v.pfx", pfx("ANTIGA LTDA:11222333000181", "senha-um", -10), "senha-um").andExpect(status().isUnprocessableEntity())).get("erro").asText()).contains("já venceu");
        assertThat(corpo(json, a1(e.gestor(), cli, "o.pfx", pfx("OUTRA SA:45723174000110", "senha-dois", 365), "senha-dois").andExpect(status().isUnprocessableEntity())).get("erro").asText()).contains("diferente do CNPJ");
        a1(e.usuario(), cli, "a.pfx", pfx, "senha-certa").andExpect(status().isForbidden());
        Empresa outra = empresa(mvc, json);
        a1(outra.admin(), cli, "a.pfx", pfx, "senha-certa").andExpect(status().isNotFound());
        ler(mvc, e.gestor(), "/api/clientes/" + cli + "/ficha").andExpect(status().isOk());
        assertThat(corpo(json, ler(mvc, e.gestor(), "/api/clientes/" + cli + "/ficha")).get("certificados")).isEmpty();
    }

    @Test
    void usoDoCertificadoFicaNaAuditoriaEApagarRemoveOArquivo() throws Exception {
        Empresa e = empresa(mvc, json);
        long cli = cliente(mvc, json, e.gestor(), "22", "Cert3 " + e.s(), null);
        JsonNode c = corpo(json, a1(e.gestor(), cli, "a.pfx", pfx("BETA:11222333000181", "pw-beta", 90), "pw-beta").andExpect(status().isCreated()));
        long idCert = c.get("id").asLong();
        // abrir para uso (o que um conector da SEFAZ faria): devolve a chave privada e registra quem usou e para quê
        long admin = corpo(json, ler(mvc, e.admin(), "/api/me")).get("id").asLong();
        var em = servico.abrirParaUso(tenantDe(e), admin, idCert, "Consulta de notas na SEFAZ");
        assertThat(em.chave()).isNotNull();
        assertThat(em.cadeia()).isNotEmpty();
        assertThat(corpo(json, ler(mvc, e.admin(), "/api/auditoria")).toString()).contains("CERTIFICADO_USADO").contains("Consulta de notas na SEFAZ");
        // outra empresa não abre
        Empresa outra = empresa(mvc, json);
        org.junit.jupiter.api.Assertions.assertThrows(br.com.viriato.comum.NaoEncontradoException.class, () -> servico.abrirParaUso(tenantDe(outra), 1L, idCert, "x"));
        // desativado não é usado
        alterar(mvc, e.gestor(), "/api/clientes/" + cli + "/certificados/" + idCert, "{\"ativo\":false}").andExpect(status().isOk());
        org.junit.jupiter.api.Assertions.assertThrows(br.com.viriato.comum.NegocioException.class, () -> servico.abrirParaUso(tenantDe(e), admin, idCert, "x"));
        // apagar remove o arquivo do armazenamento
        assertThat(MEMORIA.keySet().stream().anyMatch(k -> k.contains("cliente-" + cli + "/certificados/"))).isTrue();
        mvc.perform(delete("/api/clientes/" + cli + "/certificados/" + idCert).header("Authorization", "Bearer " + e.gestor())).andExpect(status().isNoContent());
        assertThat(MEMORIA.keySet().stream().anyMatch(k -> k.contains("cliente-" + cli + "/certificados/"))).isFalse();
    }

    @Autowired br.com.viriato.usuario.UsuarioRepository usuarios;

    private long tenantDe(Empresa e) throws Exception {
        long id = corpo(json, ler(mvc, e.admin(), "/api/me")).get("id").asLong();
        return usuarios.findById(id).orElseThrow().getTenantId();
    }

    @Test
    void a3EhSoInformativoEApagarEmpresaLimpaOCofre() throws Exception {
        Empresa e = empresa(mvc, json);
        long cli = cliente(mvc, json, e.gestor(), "23", "Cert4 " + e.s(), null);
        enviar(mvc, e.gestor(), "/api/clientes/" + cli + "/certificados/a3", "{\"titular\":\"\",\"validoAte\":\"2030-01-01\"}").andExpect(status().isUnprocessableEntity());
        JsonNode c = corpo(json, enviar(mvc, e.gestor(), "/api/clientes/" + cli + "/certificados/a3", "{\"titular\":\"João Contador\",\"emissora\":\"Serasa\",\"numeroSerie\":\"ABC123\",\"validoAte\":\"2030-01-01\"}").andExpect(status().isCreated()));
        assertThat(c.get("tipo").asText()).isEqualTo("A3");
        org.junit.jupiter.api.Assertions.assertThrows(br.com.viriato.comum.NegocioException.class, () -> servico.abrirParaUso(tenantDe(e), 1L, c.get("id").asLong(), "x"));   // A3 não é do sistema

        a1(e.gestor(), cli, "b.pfx", pfx("GAMA:11222333000181", "senha-pw", 90), "senha-pw").andExpect(status().isCreated());
        assertThat(MEMORIA.keySet().stream().anyMatch(k -> k.contains("cliente-" + cli + "/certificados/"))).isTrue();
        mvc.perform(delete("/api/clientes/" + cli).header("Authorization", "Bearer " + e.gestor())).andExpect(status().isNoContent());
        assertThat(MEMORIA.keySet().stream().anyMatch(k -> k.contains("cliente-" + cli + "/certificados/"))).isFalse();
    }

    // ---------- procurações ----------

    private ResultActions procuracao(String t, long cli, String tipo, String outorga, String venc, byte[] pdf, String nomeArq) throws Exception {
        var req = multipart("/api/clientes/" + cli + "/procuracoes");
        if (pdf != null) req.file(new MockMultipartFile("arquivo", nomeArq, "application/pdf", pdf));
        req.param("tipo", tipo).param("dataOutorga", outorga).param("poderes", "Consultar e transmitir");
        if (venc != null) req.param("dataVencimento", venc);
        return mvc.perform(req.header("Authorization", "Bearer " + t));
    }

    @Test
    void procuracaoComComprovanteVencimentoEDownload() throws Exception {
        Empresa e = empresa(mvc, json);
        long cli = cliente(mvc, json, e.gestor(), "24", "Proc " + e.s(), null);
        String venc = java.time.LocalDate.now().plusDays(10).toString();
        JsonNode p = corpo(json, procuracao(e.gestor(), cli, "ECAC", "2026-01-10", venc, "%PDF-1.4 comprovante".getBytes(), "procuracao ecac.pdf").andExpect(status().isCreated()));
        assertThat(p.get("tipo").asText()).isEqualTo("ECAC");
        assertThat(p.get("temComprovante").asBoolean()).isTrue();
        assertThat(p.get("situacao").asText()).isEqualTo("VENCENDO");
        assertThat(p.get("diasParaVencer").asLong()).isEqualTo(10);
        byte[] baixado = ler(mvc, e.gestor(), "/api/clientes/" + cli + "/procuracoes/" + p.get("id").asLong() + "/comprovante").andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(baixado)).isEqualTo("%PDF-1.4 comprovante");

        procuracao(e.gestor(), cli, "SEFAZ", "2026-05-01", "2026-04-01", null, null).andExpect(status().isUnprocessableEntity());             // vence antes de começar
        procuracao(e.gestor(), cli, "SEFAZ", "2026-01-01", null, "texto".getBytes(), "x.pdf").andExpect(status().isUnprocessableEntity());   // não é PDF de verdade
        procuracao(e.gestor(), cli, "SEFAZ", "2026-01-01", null, "%PDF-1.4".getBytes(), "x.png").andExpect(status().isUnprocessableEntity());   // só PDF
        procuracao(e.gestor(), cli, "INVALIDA", "2026-01-01", null, null, null).andExpect(status().isBadRequest());
        procuracao(e.usuario(), cli, "ECAC", "2026-01-01", null, null, null).andExpect(status().isForbidden());
        Empresa outra = empresa(mvc, json);
        ler(mvc, outra.admin(), "/api/clientes/" + cli + "/procuracoes/" + p.get("id").asLong() + "/comprovante").andExpect(status().isNotFound());

        JsonNode sem = corpo(json, procuracao(e.gestor(), cli, "FGTS_CONECTIVIDADE", "2026-01-01", null, null, null).andExpect(status().isCreated()));
        assertThat(sem.get("situacao").asText()).isEqualTo("SEM_VENCIMENTO");
        JsonNode alt = corpo(json, alterar(mvc, e.gestor(), "/api/clientes/" + cli + "/procuracoes/" + p.get("id").asLong(), "{\"dataVencimento\":\"" + java.time.LocalDate.now().minusDays(1) + "\"}").andExpect(status().isOk()));
        assertThat(alt.get("situacao").asText()).isEqualTo("VENCIDA");
        mvc.perform(delete("/api/clientes/" + cli + "/procuracoes/" + p.get("id").asLong()).header("Authorization", "Bearer " + e.gestor())).andExpect(status().isNoContent());
        assertThat(corpo(json, ler(mvc, e.gestor(), "/api/clientes/" + cli + "/ficha")).get("procuracoes")).hasSize(1);
    }
}
