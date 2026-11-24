package br.com.viriato;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Gera XMLs fiscais de exemplo nos leiautes oficiais (reduzidos ao que o leitor usa). */
final class XmlsFiscais {
    private XmlsFiscais() {}

    static String chave(String prefixo, long n) { return String.format("%-44s", prefixo + n).replace(' ', '0').substring(0, 44); }

    static byte[] nfe(String chave, String cnpjEmit, String cnpjDest, String nNF, String dhEmi, String vNF, String modelo) {
        return ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><nfeProc xmlns=\"http://www.portalfiscal.inf.br/nfe\" versao=\"4.00\"><NFe><infNFe Id=\"NFe" + chave + "\" versao=\"4.00\">"
                + "<ide><cUF>35</cUF><mod>" + modelo + "</mod><serie>1</serie><nNF>" + nNF + "</nNF><dhEmi>" + dhEmi + "</dhEmi></ide>"
                + "<emit><CNPJ>" + cnpjEmit + "</CNPJ><xNome>Fornecedor Ltda</xNome></emit>"
                + "<dest><CNPJ>" + cnpjDest + "</CNPJ><xNome>Cliente Comprador SA</xNome></dest>"
                + "<det nItem=\"1\"><prod><cProd>1</cProd><NCM>84713012</NCM><CFOP>5102</CFOP><vProd>100.00</vProd></prod></det>"
                + "<det nItem=\"2\"><prod><cProd>2</cProd><NCM>85171231</NCM><CFOP>5102</CFOP><vProd>50.00</vProd></prod></det>"
                + "<det nItem=\"3\"><prod><cProd>3</cProd><NCM>85171231</NCM><CFOP>5405</CFOP><vProd>10.00</vProd></prod></det>"
                + "<total><ICMSTot><vICMS>18.50</vICMS><vIPI>5.25</vIPI><vPIS>1.65</vPIS><vCOFINS>7.60</vCOFINS><vNF>" + vNF + "</vNF></ICMSTot></total>"
                + "</infNFe></NFe><protNFe><infProt><chNFe>" + chave + "</chNFe></infProt></protNFe></nfeProc>").getBytes(StandardCharsets.UTF_8);
    }

    static byte[] cte(String chave, String cnpjEmit, String cnpjDest, String nCT, String dhEmi, String vTPrest) {
        return ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><cteProc xmlns=\"http://www.portalfiscal.inf.br/cte\"><CTe><infCte Id=\"CTe" + chave + "\">"
                + "<ide><CFOP>6353</CFOP><mod>57</mod><serie>1</serie><nCT>" + nCT + "</nCT><dhEmi>" + dhEmi + "</dhEmi></ide>"
                + "<emit><CNPJ>" + cnpjEmit + "</CNPJ><xNome>Transportes Rápidos</xNome></emit><dest><CNPJ>" + cnpjDest + "</CNPJ><xNome>Destino</xNome></dest>"
                + "<vPrest><vTPrest>" + vTPrest + "</vTPrest></vPrest><imp><ICMS><ICMS00><vICMS>12.00</vICMS></ICMS00></ICMS></imp>"
                + "</infCte></CTe></cteProc>").getBytes(StandardCharsets.UTF_8);
    }

    static byte[] nfseAbrasf(String cnpjPrest, String cnpjTomador, String numero, String data, String valor, String iss) {
        return ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><CompNfse xmlns=\"http://www.abrasf.org.br/nfse.xsd\"><Nfse><InfNfse><Numero>" + numero + "</Numero><CodigoVerificacao>ABC123</CodigoVerificacao>"
                + "<DataEmissao>" + data + "</DataEmissao><Servico><Valores><ValorServicos>" + valor + "</ValorServicos><ValorIss>" + iss + "</ValorIss></Valores></Servico>"
                + "<PrestadorServico><IdentificacaoPrestador><Cnpj>" + cnpjPrest + "</Cnpj></IdentificacaoPrestador><RazaoSocial>Prestadora de Serviços ME</RazaoSocial></PrestadorServico>"
                + "<TomadorServico><IdentificacaoTomador><CpfCnpj><Cnpj>" + cnpjTomador + "</Cnpj></CpfCnpj></IdentificacaoTomador><RazaoSocial>Tomadora SA</RazaoSocial></TomadorServico>"
                + "</InfNfse></Nfse></CompNfse>").getBytes(StandardCharsets.UTF_8);
    }

    static byte[] nfseNacional(String id, String cnpjPrest, String cnpjToma, String nNFSe, String dhEmi, String vServ, String vIss) {
        return ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><NFSe xmlns=\"http://www.sped.fazenda.gov.br/nfse\"><infNFSe Id=\"" + id + "\"><nNFSe>" + nNFSe + "</nNFSe>"
                + "<emit><CNPJ>" + cnpjPrest + "</CNPJ><xNome>Prestador Nacional</xNome></emit><valores><vISSQN>" + vIss + "</vISSQN><vLiq>" + vServ + "</vLiq></valores>"
                + "<DPS><infDPS><dhEmi>" + dhEmi + "</dhEmi><serie>7</serie><toma><CNPJ>" + cnpjToma + "</CNPJ><xNome>Tomador Nacional</xNome></toma><valores><vServPrest><vServ>" + vServ + "</vServ></vServPrest></valores></infDPS></DPS>"
                + "</infNFSe></NFSe>").getBytes(StandardCharsets.UTF_8);
    }

    static byte[] zip(String[] nomes, byte[][] conteudos) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            for (int i = 0; i < nomes.length; i++) { z.putNextEntry(new ZipEntry(nomes[i])); z.write(conteudos[i]); z.closeEntry(); }
        }
        return out.toByteArray();
    }
}
