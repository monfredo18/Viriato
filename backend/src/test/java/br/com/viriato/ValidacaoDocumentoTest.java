package br.com.viriato;

import static br.com.viriato.Crm.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.Deflater;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Conferência do anexo antes de confirmar: CNPJ e período (MM/AAAA) do documento contra a empresa e a competência do processo. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(Crm.StorageFake.class)
class ValidacaoDocumentoTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    static final String CNPJ = "11222333000181", OUTRO = "45723174000110";

    private static byte[] pdf(String texto, boolean compactado) throws Exception {
        String conteudo = "BT /F1 12 Tf 72 700 Td (" + texto + ") Tj ET";
        byte[] corpo = conteudo.getBytes(StandardCharsets.ISO_8859_1);
        String filtro = "";
        if (compactado) {
            Deflater d = new Deflater();
            d.setInput(corpo);
            d.finish();
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[1024];
            while (!d.finished()) o.write(b, 0, d.deflate(b));
            corpo = o.toByteArray();
            filtro = "/Filter /FlateDecode ";
        }
        ByteArrayOutputStream pdf = new ByteArrayOutputStream();
        pdf.write(("%PDF-1.4\n1 0 obj\n<< " + filtro + "/Length " + corpo.length + " >>\nstream\n").getBytes(StandardCharsets.ISO_8859_1));
        pdf.write(corpo);
        pdf.write("\nendstream\nendobj\ntrailer\n<< /Root 1 0 R >>\n%%EOF\n".getBytes(StandardCharsets.ISO_8859_1));
        return pdf.toByteArray();
    }

    /** Empresa com CNPJ, uma obrigação e o processo de 10/2026 gerado por ela. Devolve {token do admin, id do processo}. */
    private Object[] cenario() throws Exception {
        Empresa e = empresa(mvc, json);
        long cli = cliente(mvc, json, e.gestor(), "95", "Val " + e.s(), "{\"cnpj\":\"" + CNPJ + "\",\"regimeTributario\":\"SIMPLES_NACIONAL\"}");
        long entrega = corpo(json, ler(mvc, e.admin(), "/api/setores")).get(0).get("entregas").get(0).get("id").asLong();
        enviar(mvc, e.admin(), "/api/obrigacoes/modelos", "{\"nome\":\"PGDAS-D\",\"periodicidade\":\"MENSAL\",\"modoVencimento\":\"DIA_DO_MES\",\"diaVencimento\":20,\"mesesDefasagem\":1,\"mesesAplicaveis\":[10],"
                + "\"regraDiaUtil\":\"ANTECIPAR_DIA_UTIL\",\"esfera\":\"FEDERAL\",\"entregaId\":" + entrega + "}").andExpect(status().isCreated());
        enviar(mvc, e.admin(), "/api/obrigacoes/gerar", "{\"ano\":2026,\"mes\":10}").andExpect(status().isOk());
        long p = corpo(json, ler(mvc, e.admin(), "/api/processos?competenciaAno=2026&competenciaMes=10")).get("content").get(0).get("id").asLong();
        return new Object[]{e.admin(), p, cli, e};
    }

    private JsonNode verificar(Object[] c, String nome, byte[] bytes) throws Exception {
        return corpo(json, tentar(c, nome, bytes).andExpect(status().isOk()));
    }

    private ResultActions tentar(Object[] c, String nome, byte[] bytes) throws Exception {
        return mvc.perform(multipart("/api/processos/" + c[1] + "/documentos/verificar").file(new MockMultipartFile("arquivo", nome, "application/octet-stream", bytes)).header("Authorization", "Bearer " + c[0]));
    }

    private static long alertas(JsonNode r) { long n = 0; for (JsonNode a : r) if (a.get("nivel").asText().equals("ALERTA")) n++; return n; }

    @Test
    void pdfDaEmpresaECompetenciaCertaNaoGeraAlerta() throws Exception {
        Object[] c = cenario();
        assertThat(alertas(verificar(c, "das.pdf", pdf("DAS Competencia 10/2026 CNPJ 11.222.333/0001-81 valor 100,00", false)))).isZero();
        assertThat(alertas(verificar(c, "das-compactado.pdf", pdf("Documento de Arrecadacao CNPJ 11222333000181 periodo 10/2026", true)))).isZero();   // fluxo compactado (Flate)
    }

    @Test
    void cnpjDeOutraEmpresaEPeriodoErradoGeramAlerta() throws Exception {
        Object[] c = cenario();
        JsonNode cnpj = verificar(c, "outro.pdf", pdf("Guia da competencia 10/2026 CNPJ 45.723.174/0001-10", true));
        assertThat(alertas(cnpj)).isEqualTo(1);
        assertThat(cnpj.get(0).get("mensagem").asText()).contains("45.723.174/0001-10").contains("11.222.333/0001-81");
        JsonNode periodo = verificar(c, "antigo.pdf", pdf("Guia da competencia 09/2026 CNPJ 11.222.333/0001-81", false));
        assertThat(alertas(periodo)).isEqualTo(1);
        assertThat(periodo.get(0).get("mensagem").asText()).contains("09/2026").contains("10/2026");
        assertThat(alertas(verificar(c, "dois.pdf", pdf("CNPJ 45.723.174/0001-10 periodo 08/2026", false)))).isEqualTo(2);
    }

    @Test
    void ofxXmlECsvSaoLidosEPdfSemTextoViraAviso() throws Exception {
        Object[] c = cenario();
        String ofx = "OFXHEADER:100\nDATA:OFXSGML\n<OFX><BANKMSGSRSV1><STMTTRNRS><STMTRS><BANKTRANLIST><DTSTART>20261001120000<DTEND>20261031120000</BANKTRANLIST>"
                + "<MEMO>Pagamento 11.222.333/0001-81</MEMO></STMTRS></STMTTRNRS></BANKMSGSRSV1></OFX>";
        assertThat(alertas(verificar(c, "extrato.ofx", ofx.getBytes()))).isZero();
        assertThat(alertas(verificar(c, "extrato-set.ofx", ofx.replace("20261001", "20260901").replace("20261031", "20260930").getBytes()))).isEqualTo(1);
        byte[] nfe = XmlsFiscais.nfe(XmlsFiscais.chave("3526", 61), "07526557000100", CNPJ, "1", "2026-10-15T10:00:00-03:00", "10.00", "55");
        assertThat(alertas(verificar(c, "nota.xml", nfe))).isZero();                                                           // emissão em 10/2026 e CNPJ da empresa
        assertThat(alertas(verificar(c, "nota-nov.xml", XmlsFiscais.nfe(XmlsFiscais.chave("3526", 62), "07526557000100", CNPJ, "2", "2026-11-15T10:00:00-03:00", "10.00", "55")))).isEqualTo(1);

        JsonNode escaneado = verificar(c, "scan.pdf", "%PDF-1.4\n1 0 obj\n<< /Type /XObject /Subtype /Image >>\nstream\n\u0001\u0002\u0003\nendstream\n%%EOF".getBytes(StandardCharsets.ISO_8859_1));
        assertThat(escaneado).hasSize(1);
        assertThat(escaneado.get(0).get("nivel").asText()).isEqualTo("INFO");
        assertThat(escaneado.get(0).get("mensagem").asText()).contains("Não consegui ler");
        JsonNode png = verificar(c, "foto.png", new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0});
        assertThat(png.get(0).get("nivel").asText()).isEqualTo("INFO");
    }

    @Test
    void verificarNaoGravaEOArquivoRuimEBarrado() throws Exception {
        Object[] c = cenario();
        long antes = MEMORIA.size();
        verificar(c, "das.pdf", pdf("CNPJ 11.222.333/0001-81 10/2026", false));
        assertThat(MEMORIA.size()).isEqualTo(antes);                                                                          // só conferiu
        tentar(c, "programa.exe", "MZ".getBytes()).andExpect(status().isUnprocessableEntity());
        tentar(c, "falso.pdf", "isto nao e pdf".getBytes()).andExpect(status().isUnprocessableEntity());
        Empresa e = (Empresa) c[3];
        int st = mvc.perform(multipart("/api/processos/" + c[1] + "/documentos/verificar").file(new MockMultipartFile("arquivo", "x.pdf", "application/pdf", pdf("x", false)))
                .header("Authorization", "Bearer " + e.usuario())).andReturn().getResponse().getStatus();
        assertThat(st).isIn(403, 404, 422);   // quem não pode mexer no processo não confere
    }
}
