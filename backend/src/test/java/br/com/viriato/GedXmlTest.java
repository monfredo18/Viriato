package br.com.viriato;

import static br.com.viriato.Crm.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** GED contábil (árvore arquivística, guarda legal, arquivamento) e importação de XMLs fiscais. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(Crm.StorageFake.class)
class GedXmlTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    static final String CNPJ = "11222333000181", OUTRO = "45723174000110", DESCONHECIDO = "07526557000100";

    private ResultActions importar(String t, Long clienteId, Object... nomesEConteudos) throws Exception {
        MockMultipartHttpServletRequestBuilder req = multipart("/api/xml/importar");
        for (int i = 0; i < nomesEConteudos.length; i += 2) req.file(new MockMultipartFile("arquivos", (String) nomesEConteudos[i], "application/xml", (byte[]) nomesEConteudos[i + 1]));
        if (clienteId != null) req.param("clienteId", String.valueOf(clienteId));
        return mvc.perform(req.header("Authorization", "Bearer " + t));
    }

    private ResultActions ged(String t, long cli, String nome, byte[] bytes, String... params) throws Exception {
        MockMultipartHttpServletRequestBuilder req = multipart("/api/clientes/" + cli + "/ged/documentos").file(new MockMultipartFile("arquivo", nome, "application/pdf", bytes));
        for (int i = 0; i < params.length; i += 2) req.param(params[i], params[i + 1]);
        return mvc.perform(req.header("Authorization", "Bearer " + t));
    }

    @Test
    void importaNotasLigaPelaEmpresaEInfereEntradaESaida() throws Exception {
        Empresa e = empresa(mvc, json);
        long cli = cliente(mvc, json, e.gestor(), "80", "Xml " + e.s(), "{\"cnpj\":\"" + CNPJ + "\"}");
        byte[] venda = XmlsFiscais.nfe(XmlsFiscais.chave("3526", 11), CNPJ, OUTRO, "100", "2026-10-15T10:00:00-03:00", "160.00", "55");          // a empresa emitiu: saída
        byte[] compra = XmlsFiscais.nfe(XmlsFiscais.chave("3526", 12), OUTRO, CNPJ, "200", "2026-10-20T10:00:00-03:00", "300.00", "55");         // a empresa recebeu: entrada
        byte[] servico = XmlsFiscais.nfseAbrasf(CNPJ, OUTRO, "7", "2026-11-03T12:00:00", "900.00", "45.00");                                     // prestou serviço: saída
        byte[] frete = XmlsFiscais.cte(XmlsFiscais.chave("3526", 13), OUTRO, CNPJ, "5", "2026-11-04T10:00:00-03:00", "400.00");                  // contratou frete: entrada

        JsonNode r = corpo(json, importar(e.gestor(), null, "venda.xml", venda, "compra.xml", compra, "servico.xml", servico, "frete.xml", frete).andExpect(status().isOk()));
        assertThat(r.get("importados").asInt()).isEqualTo(4);
        assertThat(r.get("rejeitados")).isEmpty();
        JsonNode lista = corpo(json, ler(mvc, e.gestor(), "/api/clientes/" + cli + "/xml").andExpect(status().isOk()));
        assertThat(lista).hasSize(4);
        java.util.Map<String, String> tipoPorModelo = new java.util.HashMap<>();
        for (JsonNode n : lista) tipoPorModelo.put(n.get("modelo").asText() + n.get("numeroNota").asText(), n.get("tipoOperacao").asText());
        assertThat(tipoPorModelo).containsEntry("NFE100", "SAIDA").containsEntry("NFE200", "ENTRADA").containsEntry("NFSE7", "SAIDA").containsEntry("CTE5", "ENTRADA");
        JsonNode nfe = null;
        for (JsonNode n : lista) if (n.get("numeroNota").asText().equals("100")) nfe = n;
        assertThat(nfe.get("competenciaAno").asInt()).isEqualTo(2026);
        assertThat(nfe.get("competenciaMes").asInt()).isEqualTo(10);
        assertThat(nfe.get("valorIcms").decimalValue()).isEqualByComparingTo("18.50");
        assertThat(nfe.get("cfops").toString()).contains("5102").contains("5405");
        assertThat(nfe.get("ncms")).hasSize(2);

        // filtros por competência e por tipo
        assertThat(corpo(json, ler(mvc, e.gestor(), "/api/clientes/" + cli + "/xml?ano=2026&mes=11"))).hasSize(2);
        assertThat(corpo(json, ler(mvc, e.gestor(), "/api/clientes/" + cli + "/xml?tipo=ENTRADA"))).hasSize(2);
        assertThat(corpo(json, ler(mvc, e.gestor(), "/api/clientes/" + cli + "/xml?tipo=SAIDA&ano=2026&mes=10"))).hasSize(1);

        // os arquivos estão na árvore arquivística, com guarda legal
        String esperado = "cliente_80_" + CNPJ + "/2026/10/geral/geral/";   // o gestor importa no próprio setor
        assertThat(MEMORIA.keySet().stream().filter(k -> k.contains(esperado) && k.endsWith("_venda.xml")).count()).isEqualTo(1);
        assertThat(MEMORIA.keySet().stream().filter(k -> k.startsWith("tenant_") && k.contains("cliente_80_" + CNPJ + "/2026/11/")).count()).isEqualTo(2);
        JsonNode docs = corpo(json, ler(mvc, e.gestor(), "/api/clientes/" + cli + "/ged/documentos?categoria=XML"));
        assertThat(docs).hasSize(4);
        assertThat(docs.get(0).get("guardaLegal").asBoolean()).isTrue();
    }

    @Test
    void repetidaDesconhecidaEDeOutraEmpresaNaoEntramEZipTemLimites() throws Exception {
        Empresa e = empresa(mvc, json);
        long cli = cliente(mvc, json, e.gestor(), "81", "Xml2 " + e.s(), "{\"cnpj\":\"" + CNPJ + "\"}");
        long outra = cliente(mvc, json, e.gestor(), "82", "Xml3 " + e.s(), "{\"cnpj\":\"" + OUTRO + "\"}");
        byte[] nota = XmlsFiscais.nfe(XmlsFiscais.chave("3526", 21), CNPJ, DESCONHECIDO, "1", "2026-10-15T10:00:00-03:00", "10.00", "55");
        assertThat(corpo(json, importar(e.admin(), null, "a.xml", nota).andExpect(status().isOk())).get("importados").asInt()).isEqualTo(1);
        JsonNode de_novo = corpo(json, importar(e.admin(), null, "a.xml", nota).andExpect(status().isOk()));
        assertThat(de_novo.get("duplicados").asInt()).isEqualTo(1);                                // a mesma chave não entra duas vezes
        assertThat(de_novo.get("importados").asInt()).isZero();
        assertThat(corpo(json, ler(mvc, e.admin(), "/api/clientes/" + cli + "/xml"))).hasSize(1);

        byte[] semDono = XmlsFiscais.nfe(XmlsFiscais.chave("3526", 22), DESCONHECIDO, "26767666000194", "2", "2026-10-15T10:00:00-03:00", "10.00", "55");
        JsonNode r = corpo(json, importar(e.admin(), null, "semdono.xml", semDono, "lixo.xml", "<a><b></a>".getBytes(), "texto.txt", "oi".getBytes()).andExpect(status().isOk()));
        assertThat(r.get("importados").asInt()).isZero();
        assertThat(r.get("rejeitados")).hasSize(3);
        assertThat(r.get("rejeitados").toString()).contains("Nenhuma empresa atendida tem o CNPJ").contains("XML inválido").contains("Só arquivos .xml ou .zip");

        // empresa indicada à mão: a nota precisa ser dela
        JsonNode errada = corpo(json, importar(e.admin(), outra, "venda.xml", XmlsFiscais.nfe(XmlsFiscais.chave("3526", 23), CNPJ, DESCONHECIDO, "3", "2026-10-15T10:00:00-03:00", "10.00", "55")).andExpect(status().isOk()));
        assertThat(errada.get("rejeitados").toString()).contains("não é da empresa");

        // .zip com XMLs, um arquivo qualquer e uma pasta de sistema
        byte[] zip = XmlsFiscais.zip(new String[]{"lote/n1.xml", "lote/n2.xml", "lote/leia-me.txt", "__MACOSX/lixo.xml"},
                new byte[][]{XmlsFiscais.nfe(XmlsFiscais.chave("3526", 24), CNPJ, DESCONHECIDO, "4", "2026-12-01T10:00:00-03:00", "10.00", "55"),
                        XmlsFiscais.nfe(XmlsFiscais.chave("3526", 25), DESCONHECIDO, CNPJ, "5", "2026-12-02T10:00:00-03:00", "20.00", "55"), "x".getBytes(), "<x/>".getBytes()});
        JsonNode rz = corpo(json, importar(e.admin(), null, "lote.zip", zip).andExpect(status().isOk()));
        assertThat(rz.get("importados").asInt()).isEqualTo(2);
        assertThat(rz.get("rejeitados")).isEmpty();

        // limite de arquivos do .zip
        String[] nomes = new String[1001];
        byte[][] corpos = new byte[1001][];
        for (int i = 0; i < 1001; i++) { nomes[i] = "n" + i + ".xml"; corpos[i] = "<a/>".getBytes(); }
        assertThat(corpo(json, importar(e.admin(), null, "muitos.zip", XmlsFiscais.zip(nomes, corpos)).andExpect(status().isOk())).get("rejeitados").toString()).contains("mais de 1000 arquivos");
        // extensão e conteúdo do próprio envio
        assertThat(corpo(json, importar(e.admin(), null, "falso.zip", "isto nao e um zip".getBytes()).andExpect(status().isOk())).get("rejeitados").toString()).contains("não corresponde");
    }

    @Test
    void permissoesEIsolamentoEntreEmpresas() throws Exception {
        Empresa e = empresa(mvc, json);
        long cli = cliente(mvc, json, e.gestor(), "83", "Perm " + e.s(), "{\"cnpj\":\"" + CNPJ + "\"}");
        byte[] nota = XmlsFiscais.nfe(XmlsFiscais.chave("3526", 31), CNPJ, DESCONHECIDO, "1", "2026-10-15T10:00:00-03:00", "10.00", "55");
        importar(e.usuario(), null, "a.xml", nota).andExpect(status().isForbidden());
        ler(mvc, e.usuario(), "/api/clientes/" + cli + "/xml").andExpect(status().isForbidden());
        ler(mvc, e.usuario(), "/api/clientes/" + cli + "/ged/pastas").andExpect(status().isForbidden());
        importar(e.gestor(), null, "a.xml", nota).andExpect(status().isOk());

        Empresa outra = empresa(mvc, json);
        ler(mvc, outra.admin(), "/api/clientes/" + cli + "/xml").andExpect(status().isNotFound());
        ler(mvc, outra.admin(), "/api/clientes/" + cli + "/ged/documentos").andExpect(status().isNotFound());
        importar(outra.admin(), cli, "a.xml", nota).andExpect(status().isNotFound());
        // a mesma chave pode existir na outra empresa do sistema (a unicidade é por empresa do sistema)
        cliente(mvc, json, outra.admin(), "83", "Perm " + outra.s(), "{\"cnpj\":\"" + CNPJ + "\"}");
        assertThat(corpo(json, importar(outra.admin(), null, "a.xml", nota).andExpect(status().isOk())).get("importados").asInt()).isEqualTo(1);
    }

    @Test
    void gedPorAnoMesSetorUploadDiretoGuardaLegalEArquivamento() throws Exception {
        Empresa e = empresa(mvc, json);
        long cli = cliente(mvc, json, e.gestor(), "84", "Ged " + e.s(), "{\"cnpj\":\"" + CNPJ + "\"}");
        byte[] pdf = "%PDF-1.4 guia".getBytes();

        ged(e.gestor(), cli, "x.exe", pdf).andExpect(status().isUnprocessableEntity());
        ged(e.gestor(), cli, "x.pdf", pdf, "categoria", "INVENTADA").andExpect(status().isUnprocessableEntity());
        ged(e.gestor(), cli, "x.pdf", pdf, "mes", "13").andExpect(status().isUnprocessableEntity());
        ged(e.gestor(), cli, "x.pdf", pdf, "setorId", "999999").andExpect(status().isNotFound());
        JsonNode guia = corpo(json, ged(e.gestor(), cli, "DAS 10-2026.pdf", pdf, "categoria", "GUIA", "ano", "2026", "mes", "10").andExpect(status().isCreated()));
        JsonNode contrato = corpo(json, ged(e.gestor(), cli, "contrato.pdf", pdf, "categoria", "CONTRATO", "ano", "2026", "mes", "3").andExpect(status().isCreated()));
        JsonNode livre = corpo(json, ged(e.gestor(), cli, "guia livre.pdf", pdf, "categoria", "GUIA", "ano", "2026", "mes", "11", "guardaLegal", "false").andExpect(status().isCreated()));
        assertThat(guia.get("guardaLegal").asBoolean()).isTrue();          // guia e comprovantes nascem em guarda legal
        assertThat(contrato.get("guardaLegal").asBoolean()).isFalse();
        assertThat(livre.get("guardaLegal").asBoolean()).isFalse();        // a pessoa pode desmarcar
        assertThat(guia.get("setorId").asLong()).isEqualTo(e.setorId());   // gestor grava no próprio setor
        assertThat(MEMORIA.keySet().stream().anyMatch(k -> k.contains("cliente_84_" + CNPJ + "/2026/10/geral/geral/") && k.endsWith("_DAS 10-2026.pdf"))).isTrue();

        JsonNode pastas = corpo(json, ler(mvc, e.gestor(), "/api/clientes/" + cli + "/ged/pastas").andExpect(status().isOk()));
        assertThat(pastas).hasSize(3);
        assertThat(pastas.get(0).get("mes").asInt()).isEqualTo(11);        // mais recente primeiro
        assertThat(pastas.get(0).get("setorNome").asText()).isEqualTo("Geral");
        assertThat(corpo(json, ler(mvc, e.gestor(), "/api/clientes/" + cli + "/ged/documentos?ano=2026&mes=10"))).hasSize(1);
        assertThat(corpo(json, ler(mvc, e.gestor(), "/api/clientes/" + cli + "/ged/documentos?categoria=GUIA"))).hasSize(2);
        assertThat(corpo(json, ler(mvc, e.gestor(), "/api/clientes/" + cli + "/ged/documentos?busca=contrato"))).hasSize(1);

        // download pelo mesmo caminho dos anexos (gestor e administrador; usuário não)
        ler(mvc, e.gestor(), "/api/documentos/" + guia.get("id").asLong() + "/download").andExpect(status().isOk());
        ler(mvc, e.usuario(), "/api/documentos/" + guia.get("id").asLong() + "/download").andExpect(status().isNotFound());

        // guarda legal: não exclui (nem o administrador, antes de 5 anos); arquiva
        long id = guia.get("id").asLong();
        String msg = corpo(json, mvc.perform(delete("/api/documentos/" + id).header("Authorization", "Bearer " + e.gestor())).andExpect(status().isUnprocessableEntity())).get("erro").asText();
        assertThat(msg).contains("guarda legal");
        mvc.perform(delete("/api/documentos/" + id).header("Authorization", "Bearer " + e.admin())).andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/documentos/" + id + "/arquivar").header("Authorization", "Bearer " + e.gestor())).andExpect(status().isOk());
        assertThat(corpo(json, ler(mvc, e.gestor(), "/api/clientes/" + cli + "/ged/documentos?ano=2026&mes=10"))).isEmpty();                     // sai da lista
        assertThat(corpo(json, ler(mvc, e.gestor(), "/api/clientes/" + cli + "/ged/documentos?ano=2026&mes=10&arquivados=true"))).hasSize(1);   // mas continua guardado
        assertThat(corpo(json, ler(mvc, e.gestor(), "/api/clientes/" + cli + "/ged/pastas"))).hasSize(2);
        assertThat(MEMORIA.keySet().stream().anyMatch(k -> k.endsWith("_DAS 10-2026.pdf"))).isTrue();
        assertThat(corpo(json, ler(mvc, e.admin(), "/api/auditoria")).toString()).contains("DOCUMENTO_ARQUIVADO");
        mvc.perform(post("/api/documentos/" + id + "/desarquivar").header("Authorization", "Bearer " + e.gestor())).andExpect(status().isOk());
        assertThat(corpo(json, ler(mvc, e.gestor(), "/api/clientes/" + cli + "/ged/pastas"))).hasSize(3);

        // sem guarda legal: exclui normalmente
        mvc.perform(delete("/api/documentos/" + contrato.get("id").asLong()).header("Authorization", "Bearer " + e.gestor())).andExpect(status().isNoContent());
        assertThat(MEMORIA.keySet().stream().anyMatch(k -> k.endsWith("_contrato.pdf"))).isFalse() ;
    }

    @Test
    void anexoDeProcessoVaiParaAArvoreEAparecenoGedEApagarAEmpresaLimpaAPasta() throws Exception {
        Empresa e = empresa(mvc, json);
        long cli = cliente(mvc, json, e.gestor(), "85", "Proc " + e.s(), "{\"cnpj\":\"" + CNPJ + "\"}");
        JsonNode geral = corpo(json, ler(mvc, e.admin(), "/api/setores")).get(0);
        long eu = corpo(json, ler(mvc, e.admin(), "/api/me")).get("id").asLong();
        long p = corpo(json, enviar(mvc, e.admin(), "/api/processos", "{\"clienteId\":" + cli + ",\"entregaId\":" + geral.get("entregas").get(0).get("id").asLong() + ",\"setorId\":" + e.setorId() + ",\"responsavelId\":" + eu + "}").andExpect(status().isCreated())).get("id").asLong();
        mvc.perform(multipart("/api/processos/" + p + "/documentos").file(new MockMultipartFile("arquivo", "recibo.pdf", "application/pdf", "%PDF-1.4 r".getBytes()))
                .param("guardaLegal", "true").param("categoria", "RECIBO").header("Authorization", "Bearer " + e.admin())).andExpect(status().isCreated());
        assertThat(MEMORIA.keySet().stream().anyMatch(k -> k.contains("cliente_85_" + CNPJ + "/") && k.contains("/geral/" + p + "/") && k.endsWith("_recibo.pdf"))).isTrue();
        JsonNode docs = corpo(json, ler(mvc, e.admin(), "/api/clientes/" + cli + "/ged/documentos"));
        assertThat(docs).hasSize(1);
        assertThat(docs.get(0).get("processoId").asLong()).isEqualTo(p);
        assertThat(docs.get(0).get("guardaLegal").asBoolean()).isTrue();

        ged(e.admin(), cli, "avulso.pdf", "%PDF-1.4 a".getBytes()).andExpect(status().isCreated());
        importar(e.admin(), null, "n.xml", XmlsFiscais.nfe(XmlsFiscais.chave("3526", 41), CNPJ, DESCONHECIDO, "1", "2026-10-15T10:00:00-03:00", "10.00", "55")).andExpect(status().isOk());
        enviar(mvc, e.admin(), "/api/processos/" + p + "/cancelar", "{\"motivo\":\"teste\"}").andExpect(status().isOk());
        mvc.perform(delete("/api/clientes/" + cli).header("Authorization", "Bearer " + e.admin())).andExpect(status().isNoContent());
        assertThat(MEMORIA.keySet().stream().anyMatch(k -> k.endsWith("_avulso.pdf") || k.endsWith("_n.xml"))).isFalse();   // a pasta da empresa sumiu
    }
}
