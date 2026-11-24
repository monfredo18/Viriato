package br.com.viriato.ged;

import br.com.viriato.comum.NegocioException;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Lê os leiautes oficiais brasileiros: NF-e (modelo 55), NFC-e (65), CT-e (57) e NFS-e (padrão nacional e ABRASF). Usa o
 * DOM nativo do Java, com DTD e entidades externas desligados (sem XXE nem "billion laughs"). Eventos, cancelamentos e
 * inutilizações não são notas e são recusados com o motivo.
 */
public final class XmlFiscalParser {
    private XmlFiscalParser() {}

    public static NotaParseada ler(byte[] xml) {
        Document doc;
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        } catch (Exception e) {
            throw new NegocioException("XML inválido ou com recursos não permitidos (DTD/entidades)");
        }
        Element raiz = doc.getDocumentElement();
        Element inf;
        if ((inf = achar(raiz, "infNFe")) != null) return nfe(inf, raiz);
        if ((inf = achar(raiz, "infCte")) != null) return cte(inf, raiz);
        if ((inf = achar(raiz, "InfNfse")) != null) return nfseAbrasf(inf);
        if ((inf = achar(raiz, "infNFSe")) != null) return nfseNacional(inf);
        String r = raiz.getLocalName();
        if (r != null && (r.startsWith("procEvento") || r.startsWith("retEvento") || r.startsWith("evento") || r.contains("inut") || r.contains("Inut"))) {
            throw new NegocioException("O arquivo é um evento ou inutilização (" + r + "), não uma nota fiscal");
        }
        throw new NegocioException("Leiaute de XML não reconhecido (esperado NF-e, NFC-e, CT-e ou NFS-e)");
    }

    // ---------- NF-e e NFC-e ----------
    private static NotaParseada nfe(Element inf, Element raiz) {
        String id = inf.getAttribute("Id");
        String chave = id.startsWith("NFe") ? id.substring(3) : texto(raiz, "chNFe");
        Element ide = achar(inf, "ide"), emit = achar(inf, "emit"), dest = achar(inf, "dest"), tot = achar(inf, "ICMSTot");
        if (chave == null || !chave.matches("\\d{44}")) throw new NegocioException("Chave de acesso da NF-e ausente ou inválida");
        String modelo = ide == null ? null : texto(ide, "mod");
        BigDecimal iss = valor(achar(inf, "ISSQNtot"), "vISS");
        Set<String> cfops = new LinkedHashSet<>(), ncms = new LinkedHashSet<>();
        for (Element det : todos(inf, "det")) {
            Element prod = achar(det, "prod");
            if (prod == null) continue;
            String c = texto(prod, "CFOP"), n = texto(prod, "NCM");
            if (c != null) cfops.add(c);
            if (n != null) ncms.add(n);
        }
        return new NotaParseada("65".equals(modelo) ? "NFCE" : "NFE", chave, texto(ide, "nNF"), texto(ide, "serie"), data(texto(ide, "dhEmi") != null ? texto(ide, "dhEmi") : texto(ide, "dEmi")),
                doc(emit), texto(emit, "xNome"), doc(dest), texto(dest, "xNome"), valor(tot, "vNF"), valor(tot, "vICMS"), valor(tot, "vIPI"), valor(tot, "vPIS"), valor(tot, "vCOFINS"), iss, cfops, ncms);
    }

    // ---------- CT-e ----------
    private static NotaParseada cte(Element inf, Element raiz) {
        String id = inf.getAttribute("Id");
        String chave = id.startsWith("CTe") ? id.substring(3) : texto(raiz, "chCTe");
        if (chave == null || !chave.matches("\\d{44}")) throw new NegocioException("Chave de acesso do CT-e ausente ou inválida");
        Element ide = achar(inf, "ide"), emit = achar(inf, "emit"), dest = achar(inf, "dest"), rem = achar(inf, "rem"), prest = achar(inf, "vPrest"), imp = achar(inf, "imp");
        Element contraparte = dest != null ? dest : rem;
        Set<String> cfops = new LinkedHashSet<>();
        if (texto(ide, "CFOP") != null) cfops.add(texto(ide, "CFOP"));
        return new NotaParseada("CTE", chave, texto(ide, "nCT"), texto(ide, "serie"), data(texto(ide, "dhEmi")), doc(emit), texto(emit, "xNome"), doc(contraparte), texto(contraparte, "xNome"),
                valor(prest, "vTPrest"), valor(imp, "vICMS"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, cfops, new LinkedHashSet<>());
    }

    // ---------- NFS-e ABRASF ----------
    private static NotaParseada nfseAbrasf(Element inf) {
        Element prest = achar(inf, "PrestadorServico"), toma = achar(inf, "TomadorServico"), valores = achar(inf, "Valores");
        String cnpjPrest = digitos(texto(achar(prest, "IdentificacaoPrestador"), "Cnpj") != null ? texto(achar(prest, "IdentificacaoPrestador"), "Cnpj") : texto(prest, "Cnpj"));
        String numero = texto(inf, "Numero"), verif = texto(inf, "CodigoVerificacao");
        if (numero == null) throw new NegocioException("NFS-e sem número");
        String chave = "NFSE-" + (cnpjPrest == null ? "0" : cnpjPrest) + "-" + numero + "-" + (verif == null ? "0" : verif);
        String tomador = digitos(texto(achar(toma, "IdentificacaoTomador"), "Cnpj"));
        return new NotaParseada("NFSE", chave.length() > 60 ? chave.substring(0, 60) : chave, numero, null, data(texto(inf, "DataEmissao")), cnpjPrest, texto(prest, "RazaoSocial"), tomador, texto(toma, "RazaoSocial"),
                valor(valores, "ValorServicos"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, valor(valores, "ValorIss"), new LinkedHashSet<>(), new LinkedHashSet<>());
    }

    // ---------- NFS-e padrão nacional ----------
    private static NotaParseada nfseNacional(Element inf) {
        String chave = inf.getAttribute("Id");
        if (chave == null || chave.isBlank()) throw new NegocioException("NFS-e sem identificador");
        Element emit = achar(inf, "emit"), toma = achar(inf, "toma"), valores = achar(inf, "valores");
        Element dps = achar(inf, "infDPS");
        BigDecimal total = dps != null && achar(dps, "vServ") != null ? valor(dps, "vServ") : valor(valores, "vLiq");
        String ref = texto(dps, "dhEmi") != null ? texto(dps, "dhEmi") : texto(inf, "dhProc");
        return new NotaParseada("NFSE", chave.length() > 60 ? chave.substring(0, 60) : chave, texto(inf, "nNFSe"), texto(dps, "serie"), data(ref), doc(emit), texto(emit, "xNome"), doc(toma), texto(toma, "xNome"),
                total, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, valor(valores, "vISSQN"), new LinkedHashSet<>(), new LinkedHashSet<>());
    }

    // ---------- utilidades ----------
    private static Element achar(Node ctx, String nome) {
        if (ctx == null) return null;
        if (ctx instanceof Element e && nome.equals(e.getLocalName())) return e;
        NodeList l = ctx instanceof Document d ? d.getElementsByTagNameNS("*", nome) : ((Element) ctx).getElementsByTagNameNS("*", nome);
        return l.getLength() == 0 ? null : (Element) l.item(0);
    }

    private static java.util.List<Element> todos(Element ctx, String nome) {
        NodeList l = ctx.getElementsByTagNameNS("*", nome);
        java.util.List<Element> out = new java.util.ArrayList<>();
        for (int i = 0; i < l.getLength(); i++) out.add((Element) l.item(i));
        return out;
    }

    private static String texto(Node ctx, String nome) {
        Element e = ctx == null ? null : (ctx instanceof Element el && nome.equals(el.getLocalName()) ? el : achar(ctx, nome));
        if (e == null) return null;
        String t = e.getTextContent();
        return t == null || t.isBlank() ? null : t.trim();
    }

    private static String doc(Element e) {
        if (e == null) return null;
        String c = texto(e, "CNPJ") != null ? texto(e, "CNPJ") : texto(e, "Cnpj");
        return c == null ? null : digitos(c);
    }

    private static String digitos(String s) { return s == null ? null : s.replaceAll("\\D", ""); }

    private static BigDecimal valor(Node ctx, String nome) {
        String t = texto(ctx, nome);
        if (t == null) return BigDecimal.ZERO;
        try { return new BigDecimal(t.replace(",", ".")); } catch (NumberFormatException e) { return BigDecimal.ZERO; }
    }

    private static LocalDate data(String iso) {
        if (iso == null || iso.length() < 10) return null;
        try { return LocalDate.parse(iso.substring(0, 10)); } catch (Exception e) { return null; }
    }
}
