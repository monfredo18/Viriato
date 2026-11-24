package br.com.viriato;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

/** Montagem do pedido SOAP e leitura da resposta do NFeDistribuicaoDFe (sem ir à SEFAZ). */
class SefazHttpConectorTest {
    private static String docZip(String xml) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        try (GZIPOutputStream g = new GZIPOutputStream(o)) { g.write(xml.getBytes(StandardCharsets.UTF_8)); }
        return Base64.getEncoder().encodeToString(o.toByteArray());
    }

    @Test
    void envelopeTemCnpjUfNsuDe15DigitosEAmbiente() throws Exception {
        var m = Class.forName("br.com.viriato.ged.SefazDfeHttpConnector").getDeclaredMethod("envelope", String.class, String.class, String.class, boolean.class);
        m.setAccessible(true);
        String x = (String) m.invoke(null, "11222333000181", "RJ", "42", true);
        assertThat(x).contains("<CNPJ>11222333000181</CNPJ>").contains("<cUFAutor>33</cUFAutor>").contains("<ultNSU>000000000000042</ultNSU>").contains("<tpAmb>2</tpAmb>").contains("versao=\"1.01\"");
        assertThat((String) m.invoke(null, "1", "SP", null, false)).contains("<ultNSU>000000000000000</ultNSU>").contains("<tpAmb>1</tpAmb>").contains("<cUFAutor>35</cUFAutor>");
    }

    @Test
    void respostaComDocZipEhDescompactada() throws Exception {
        String soap = "<soap:Envelope xmlns:soap=\"http://www.w3.org/2003/05/soap-envelope\"><soap:Body><nfeDistDFeInteresseResponse xmlns=\"http://www.portalfiscal.inf.br/nfe/wsdl/NFeDistribuicaoDFe\"><nfeDistDFeInteresseResult>"
                + "<retDistDFeInt xmlns=\"http://www.portalfiscal.inf.br/nfe\"><cStat>138</cStat><xMotivo>Documento(s) localizado(s)</xMotivo><ultNSU>000000000000007</ultNSU><maxNSU>000000000000009</maxNSU>"
                + "<loteDistDFeInt><docZip NSU=\"000000000000007\" schema=\"procNFe_v4.00.xsd\">" + docZip("<nfeProc><x>ok</x></nfeProc>") + "</docZip></loteDistDFeInt></retDistDFeInt></nfeDistDFeInteresseResult></nfeDistDFeInteresseResponse></soap:Body></soap:Envelope>";
        var m = Class.forName("br.com.viriato.ged.SefazDfeHttpConnector").getDeclaredMethod("interpretar", byte[].class);
        m.setAccessible(true);
        var r = (br.com.viriato.ged.SefazDfeConnector.Resposta) m.invoke(null, (Object) soap.getBytes(StandardCharsets.UTF_8));
        assertThat(r.codigoStatus()).isEqualTo("138");
        assertThat(r.ultimoNsu()).isEqualTo("000000000000007");
        assertThat(r.maxNsu()).isEqualTo("000000000000009");
        assertThat(r.documentos()).hasSize(1);
        assertThat(r.documentos().get(0).esquema()).isEqualTo("procNFe_v4.00.xsd");
        assertThat(new String(r.documentos().get(0).xml(), StandardCharsets.UTF_8)).isEqualTo("<nfeProc><x>ok</x></nfeProc>");
    }
}
