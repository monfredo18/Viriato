package br.com.viriato.ged;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Cliente SOAP do NFeDistribuicaoDFe (leiaute 1.01). Monta o pedido a partir do último NSU e lê a resposta (docZip: base64 + gzip),
 * devolvendo os XMLs já descompactados. A chamada com TLS mútuo é feita pelo agente no computador onde está o certificado (ele
 * confia nas autoridades do Windows e, se preciso, em um armazém indicado na configuração do agente). Não foi exercitado contra a
 * SEFAZ real neste projeto (só com respostas simuladas).
 */
public class SefazDfeHttpConnector implements SefazDfeConnector {
    static final Map<String, String> CODIGO_UF = Map.ofEntries(Map.entry("RO", "11"), Map.entry("AC", "12"), Map.entry("AM", "13"), Map.entry("RR", "14"), Map.entry("PA", "15"), Map.entry("AP", "16"),
            Map.entry("TO", "17"), Map.entry("MA", "21"), Map.entry("PI", "22"), Map.entry("CE", "23"), Map.entry("RN", "24"), Map.entry("PB", "25"), Map.entry("PE", "26"), Map.entry("AL", "27"),
            Map.entry("SE", "28"), Map.entry("BA", "29"), Map.entry("MG", "31"), Map.entry("ES", "32"), Map.entry("RJ", "33"), Map.entry("SP", "35"), Map.entry("PR", "41"), Map.entry("SC", "42"),
            Map.entry("RS", "43"), Map.entry("MS", "50"), Map.entry("MT", "51"), Map.entry("GO", "52"), Map.entry("DF", "53"));

    private final String url;
    private final boolean homologacao;

    public SefazDfeHttpConnector(String url, boolean homologacao) {
        this.url = url;
        this.homologacao = homologacao;
    }

    @Override public boolean habilitado() { return true; }

    /** Corpo SOAP do pedido (público para teste). */
    static String envelope(String cnpj, String uf, String ultimoNsu, boolean homologacao) {
        String nsu = String.format("%15s", ultimoNsu == null || ultimoNsu.isBlank() ? "0" : ultimoNsu).replace(' ', '0');
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><soap12:Envelope xmlns:soap12=\"http://www.w3.org/2003/05/soap-envelope\"><soap12:Body>"
                + "<nfeDistDFeInteresse xmlns=\"http://www.portalfiscal.inf.br/nfe/wsdl/NFeDistribuicaoDFe\"><nfeDadosMsg>"
                + "<distDFeInt xmlns=\"http://www.portalfiscal.inf.br/nfe\" versao=\"1.01\"><tpAmb>" + (homologacao ? "2" : "1") + "</tpAmb><cUFAutor>" + CODIGO_UF.getOrDefault(uf == null ? "" : uf.toUpperCase(), "35")
                + "</cUFAutor><CNPJ>" + cnpj + "</CNPJ><distNSU><ultNSU>" + nsu + "</ultNSU></distNSU></distDFeInt></nfeDadosMsg></nfeDistDFeInteresse></soap12:Body></soap12:Envelope>";
    }

    @Override
    public Resposta consultar(Postador postador, String cnpj, String uf, String ultimoNsu) throws IOException {
        byte[] corpo = postador.postar(url, "application/soap+xml; charset=utf-8", envelope(cnpj, uf, ultimoNsu, homologacao).getBytes(StandardCharsets.UTF_8));
        try {
            return interpretar(corpo);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Resposta da SEFAZ ilegível: " + e.getMessage(), e);
        }
    }

    /** Lê a resposta SOAP (retDistDFeInt); público para teste com respostas simuladas. */
    static Resposta interpretar(byte[] soap) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        Document d = f.newDocumentBuilder().parse(new ByteArrayInputStream(soap));
        String cStat = texto(d.getDocumentElement(), "cStat"), motivo = texto(d.getDocumentElement(), "xMotivo");
        if (cStat == null) throw new IOException("Resposta da SEFAZ sem código de status");
        List<DocumentoDfe> docs = new ArrayList<>();
        NodeList zips = d.getElementsByTagNameNS("*", "docZip");
        for (int i = 0; i < zips.getLength(); i++) {
            Element z = (Element) zips.item(i);
            byte[] comprimido = Base64.getMimeDecoder().decode(z.getTextContent().trim());
            try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(comprimido))) {
                docs.add(new DocumentoDfe(z.getAttribute("NSU"), z.getAttribute("schema"), gz.readNBytes(5 * 1024 * 1024)));
            }
        }
        return new Resposta(cStat, motivo, texto(d.getDocumentElement(), "ultNSU"), texto(d.getDocumentElement(), "maxNSU"), docs);
    }

    private static String texto(Element raiz, String nome) {
        NodeList l = raiz.getElementsByTagNameNS("*", nome);
        return l.getLength() == 0 ? null : l.item(0).getTextContent().trim();
    }
}
