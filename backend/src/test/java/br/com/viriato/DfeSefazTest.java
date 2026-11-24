package br.com.viriato;

import static br.com.viriato.Crm.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.viriato.crm.CertificadoService.CertificadoEmUso;
import br.com.viriato.ged.DfeControleRepository;
import br.com.viriato.ged.DfeService;
import br.com.viriato.ged.SefazDfeConnector;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** Consulta à SEFAZ (Distribuição DF-e) com o certificado do cofre, controle de NSU e espera de 1 hora (conector simulado). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({Crm.StorageFake.class, DfeSefazTest.Falso.class})
class DfeSefazTest {
    record Chamada(String cnpj, String uf, String nsu, boolean temChave) {}

    static final List<Chamada> CHAMADAS = new ArrayList<>();
    static final Deque<SefazDfeConnector.Resposta> ROTEIRO = new ArrayDeque<>();

    @TestConfiguration
    static class Falso {
        @Bean @Primary
        SefazDfeConnector sefazFalso() {
            return new SefazDfeConnector() {
                public boolean habilitado() { return true; }
                public Resposta consultar(CertificadoEmUso cert, String cnpj, String uf, String nsu) {
                    CHAMADAS.add(new Chamada(cnpj, uf, nsu, cert.chave() != null && cert.cadeia().length > 0));
                    return ROTEIRO.poll();
                }
            };
        }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired DfeService dfe;
    @Autowired DfeControleRepository controles;

    static final String CNPJ = "11222333000181";

    private long empresaComCertificado(Empresa e, String numero) throws Exception {
        long cli = cliente(mvc, json, e.gestor(), numero, "Dfe " + e.s(), "{\"cnpj\":\"" + CNPJ + "\",\"uf\":\"SP\"}");
        mvc.perform(multipart("/api/clientes/" + cli + "/certificados/a1").file(new MockMultipartFile("arquivo", "c.pfx", "application/x-pkcs12", pfx("EMPRESA TESTE:" + CNPJ, "senha-do-dfe", 200)))
                .param("senha", "senha-do-dfe").header("Authorization", "Bearer " + e.gestor())).andExpect(status().isCreated());
        return cli;
    }

    private static SefazDfeConnector.DocumentoDfe doc(String nsu, String esquema, byte[] xml) { return new SefazDfeConnector.DocumentoDfe(nsu, esquema, xml); }

    @Test
    void baixaNotasImportaIgnoraResumosGuardaNsuEEsperaUmaHora() throws Exception {
        Empresa e = empresa(mvc, json);
        long cli = empresaComCertificado(e, "90");
        CHAMADAS.clear(); ROTEIRO.clear();
        byte[] n1 = XmlsFiscais.nfe(XmlsFiscais.chave("3526", 51), "07526557000100", CNPJ, "10", "2026-10-10T10:00:00-03:00", "100.00", "55");
        byte[] n2 = XmlsFiscais.nfe(XmlsFiscais.chave("3526", 52), "07526557000100", CNPJ, "11", "2026-10-11T10:00:00-03:00", "200.00", "55");
        ROTEIRO.add(new SefazDfeConnector.Resposta("138", "Documento(s) localizado(s)", "000000000000002", "000000000000004",
                List.of(doc("000000000000001", "procNFe_v4.00.xsd", n1), doc("000000000000002", "resNFe_v1.01.xsd", "<resNFe/>".getBytes()))));
        ROTEIRO.add(new SefazDfeConnector.Resposta("138", "Documento(s) localizado(s)", "000000000000004", "000000000000004",
                List.of(doc("000000000000003", "procNFe_v4.00.xsd", n2), doc("000000000000004", "procNFe_v4.00.xsd", n1))));   // n1 repetida: duplicada

        JsonNode r = corpo(json, enviar(mvc, e.gestor(), "/api/clientes/" + cli + "/sefaz/consultar", "{}").andExpect(status().isOk()));
        assertThat(r.get("importadas").asInt()).isEqualTo(2);
        assertThat(r.get("duplicadas").asInt()).isEqualTo(1);
        assertThat(r.get("resumosIgnorados").asInt()).isEqualTo(1);
        assertThat(r.get("ultimoNsu").asText()).isEqualTo("000000000000004");
        assertThat(CHAMADAS).hasSize(2);
        assertThat(CHAMADAS.get(0)).isEqualTo(new Chamada(CNPJ, "SP", "0", true));                     // a primeira parte do zero, com a chave do certificado
        assertThat(CHAMADAS.get(1).nsu()).isEqualTo("000000000000002");                                  // a seguinte continua de onde parou

        JsonNode notas = corpo(json, ler(mvc, e.gestor(), "/api/clientes/" + cli + "/xml"));
        assertThat(notas).hasSize(2);
        assertThat(notas.get(0).get("origem").asText()).isEqualTo("SEFAZ");
        assertThat(notas.get(0).get("tipoOperacao").asText()).isEqualTo("ENTRADA");                     // a empresa é a destinatária
        assertThat(corpo(json, ler(mvc, e.admin(), "/api/auditoria")).toString()).contains("CERTIFICADO_USADO").contains("Consulta de notas na SEFAZ");

        // nada novo: guarda a espera de 1 hora e recusa nova consulta até lá
        ROTEIRO.add(new SefazDfeConnector.Resposta("137", "Nenhum documento localizado", "000000000000004", "000000000000004", List.of()));
        JsonNode vazio = corpo(json, enviar(mvc, e.gestor(), "/api/clientes/" + cli + "/sefaz/consultar", "{}").andExpect(status().isOk()));
        assertThat(vazio.get("status").asText()).isEqualTo("SEM_DOCUMENTOS");
        JsonNode estado = corpo(json, ler(mvc, e.gestor(), "/api/clientes/" + cli + "/sefaz").andExpect(status().isOk()));
        assertThat(estado.get("habilitado").asBoolean()).isTrue();
        assertThat(estado.get("proximaConsulta").isNull()).isFalse();
        String msg = corpo(json, enviar(mvc, e.gestor(), "/api/clientes/" + cli + "/sefaz/consultar", "{}").andExpect(status().isUnprocessableEntity())).get("erro").asText();
        assertThat(msg).contains("esperar");
        assertThat(CHAMADAS).hasSize(3);                                                                  // a terceira consulta nem chegou à SEFAZ
    }

    @Test
    void semCertificadoSemCnpjPermissoesEIsolamento() throws Exception {
        Empresa e = empresa(mvc, json);
        long semCert = cliente(mvc, json, e.gestor(), "91", "Sem " + e.s(), "{\"cnpj\":\"" + CNPJ + "\"}");
        long semCnpj = cliente(mvc, json, e.gestor(), "92", "Sem2 " + e.s(), null);
        assertThat(corpo(json, enviar(mvc, e.gestor(), "/api/clientes/" + semCert + "/sefaz/consultar", "{}").andExpect(status().isUnprocessableEntity())).get("erro").asText()).contains("certificado A1");
        assertThat(corpo(json, enviar(mvc, e.gestor(), "/api/clientes/" + semCnpj + "/sefaz/consultar", "{}").andExpect(status().isUnprocessableEntity())).get("erro").asText()).contains("CNPJ");
        enviar(mvc, e.usuario(), "/api/clientes/" + semCert + "/sefaz/consultar", "{}").andExpect(status().isForbidden());
        ler(mvc, e.usuario(), "/api/clientes/" + semCert + "/sefaz").andExpect(status().isForbidden());
        Empresa outra = empresa(mvc, json);
        enviar(mvc, outra.admin(), "/api/clientes/" + semCert + "/sefaz/consultar", "{}").andExpect(status().isNotFound());
    }

    @Test
    void erroDaSefazFicaRegistradoESemEsperaSeRespostaVaziaForDiferente() throws Exception {
        Empresa e = empresa(mvc, json);
        long cli = empresaComCertificado(e, "93");
        ROTEIRO.clear();
        ROTEIRO.add(new SefazDfeConnector.Resposta("656", "Consumo Indevido", "0", "0", List.of()));
        JsonNode r = corpo(json, enviar(mvc, e.gestor(), "/api/clientes/" + cli + "/sefaz/consultar", "{}").andExpect(status().isOk()));
        assertThat(r.get("status").asText()).isEqualTo("ESPERAR");
        assertThat(r.get("mensagem").asText()).contains("656");
    }
}
