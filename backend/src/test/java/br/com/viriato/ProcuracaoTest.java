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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Procurações com comprovante (os certificados digitais têm o teste próprio do agente local). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(Crm.StorageFake.class)
class ProcuracaoTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

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
