package br.com.viriato;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.viriato.comum.NegocioException;
import br.com.viriato.ged.NotaParseada;
import br.com.viriato.ged.XmlFiscalParser;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** Leitor de XML fiscal: NF-e, NFC-e, CT-e e NFS-e (ABRASF e nacional), e a segurança do próprio XML. */
class XmlFiscalParserTest {
    static final String K = XmlsFiscais.chave("3526", 1);

    @Test
    void nfe55ExtraiCabecalhoImpostosCfopsENcms() {
        NotaParseada n = XmlFiscalParser.ler(XmlsFiscais.nfe(K, "11222333000181", "45723174000110", "1234", "2026-10-15T10:30:00-03:00", "160.00", "55"));
        assertThat(n.modelo()).isEqualTo("NFE");
        assertThat(n.chave()).isEqualTo(K).hasSize(44);
        assertThat(n.numero()).isEqualTo("1234");
        assertThat(n.serie()).isEqualTo("1");
        assertThat(n.dataEmissao()).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(n.cnpjEmitente()).isEqualTo("11222333000181");
        assertThat(n.nomeEmitente()).isEqualTo("Fornecedor Ltda");
        assertThat(n.cnpjDestinatario()).isEqualTo("45723174000110");
        assertThat(n.valorTotal()).isEqualByComparingTo("160.00");
        assertThat(n.icms()).isEqualByComparingTo("18.50");
        assertThat(n.ipi()).isEqualByComparingTo("5.25");
        assertThat(n.pis()).isEqualByComparingTo("1.65");
        assertThat(n.cofins()).isEqualByComparingTo("7.60");
        assertThat(n.cfops()).containsExactly("5102", "5405");
        assertThat(n.ncms()).containsExactly("84713012", "85171231");
    }

    @Test
    void nfce65ComDataAntigaEhReconhecidaPeloModelo() {
        NotaParseada n = XmlFiscalParser.ler(XmlsFiscais.nfe(XmlsFiscais.chave("3526", 2), "11222333000181", "45723174000110", "9", "2026-03-02T08:00:00-03:00", "10.00", "65"));
        assertThat(n.modelo()).isEqualTo("NFCE");
    }

    @Test
    void cte57() {
        NotaParseada n = XmlFiscalParser.ler(XmlsFiscais.cte(XmlsFiscais.chave("3526", 3), "11222333000181", "45723174000110", "77", "2026-10-20T09:00:00-03:00", "950.00"));
        assertThat(n.modelo()).isEqualTo("CTE");
        assertThat(n.numero()).isEqualTo("77");
        assertThat(n.valorTotal()).isEqualByComparingTo("950.00");
        assertThat(n.icms()).isEqualByComparingTo("12.00");
        assertThat(n.cfops()).containsExactly("6353");
        assertThat(n.cnpjDestinatario()).isEqualTo("45723174000110");
    }

    @Test
    void nfseAbrasfENacional() {
        NotaParseada a = XmlFiscalParser.ler(XmlsFiscais.nfseAbrasf("11222333000181", "45723174000110", "555", "2026-10-05T12:00:00", "2500.00", "125.00"));
        assertThat(a.modelo()).isEqualTo("NFSE");
        assertThat(a.chave()).isEqualTo("NFSE-11222333000181-555-ABC123");
        assertThat(a.cnpjEmitente()).isEqualTo("11222333000181");
        assertThat(a.cnpjDestinatario()).isEqualTo("45723174000110");
        assertThat(a.valorTotal()).isEqualByComparingTo("2500.00");
        assertThat(a.iss()).isEqualByComparingTo("125.00");
        assertThat(a.dataEmissao()).isEqualTo(LocalDate.of(2026, 10, 5));

        NotaParseada n = XmlFiscalParser.ler(XmlsFiscais.nfseNacional("NFS35503082211222333000181000000000000126100000000001", "11222333000181", "45723174000110", "12", "2026-09-30T08:00:00-03:00", "800.00", "40.00"));
        assertThat(n.modelo()).isEqualTo("NFSE");
        assertThat(n.chave()).startsWith("NFS3550308");
        assertThat(n.numero()).isEqualTo("12");
        assertThat(n.valorTotal()).isEqualByComparingTo("800.00");
        assertThat(n.iss()).isEqualByComparingTo("40.00");
        assertThat(n.cnpjDestinatario()).isEqualTo("45723174000110");
        assertThat(n.dataEmissao()).isEqualTo(LocalDate.of(2026, 9, 30));
    }

    @Test
    void recusaXmlMalFormadoComDtdEntidadesEventosEChaveInvalida() {
        assertThatThrownBy(() -> XmlFiscalParser.ler("<a><b></a>".getBytes())).isInstanceOf(NegocioException.class).hasMessageContaining("XML inválido");
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><nfeProc><NFe><infNFe Id=\"NFe" + K + "\"><ide><mod>55</mod></ide><emit><xNome>&e;</xNome></emit></infNFe></NFe></nfeProc>";
        assertThatThrownBy(() -> XmlFiscalParser.ler(xxe.getBytes(StandardCharsets.UTF_8))).isInstanceOf(NegocioException.class).hasMessageContaining("DTD");
        String bomba = "<?xml version=\"1.0\"?><!DOCTYPE l [<!ENTITY a \"aaaaaaaaaa\"><!ENTITY b \"&a;&a;&a;&a;&a;&a;&a;&a;\">]><l>&b;</l>";
        assertThatThrownBy(() -> XmlFiscalParser.ler(bomba.getBytes())).isInstanceOf(NegocioException.class);
        assertThatThrownBy(() -> XmlFiscalParser.ler("<procEventoNFe xmlns=\"http://www.portalfiscal.inf.br/nfe\"><evento/></procEventoNFe>".getBytes())).hasMessageContaining("evento");
        assertThatThrownBy(() -> XmlFiscalParser.ler("<foo><bar/></foo>".getBytes())).hasMessageContaining("não reconhecido");
        assertThatThrownBy(() -> XmlFiscalParser.ler(XmlsFiscais.nfe("123", "1", "2", "1", "2026-01-01T00:00:00", "1", "55"))).hasMessageContaining("Chave de acesso");
    }

    @Test
    void numeroSemValorViraZero() {
        String semTotais = new String(XmlsFiscais.nfe(K, "11222333000181", "45723174000110", "1", "2026-10-15T10:00:00", "abc", "55"), StandardCharsets.UTF_8);
        assertThat(XmlFiscalParser.ler(semTotais.getBytes(StandardCharsets.UTF_8)).valorTotal()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
