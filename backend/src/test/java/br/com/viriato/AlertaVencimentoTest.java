package br.com.viriato;

import static br.com.viriato.Crm.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.viriato.crm.AlertaVencimentoService;
import br.com.viriato.crm.ClienteCertificadoRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/** Alertas de 30/15/5 dias e vencido (um por marco) e o painel de saúde cadastral. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(Crm.StorageFake.class)
class AlertaVencimentoTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AlertaVencimentoService alertas;
    @Autowired ClienteCertificadoRepository certs;

    private long a3(String t, long cli, LocalDate ate) throws Exception {
        return corpo(json, enviar(mvc, t, "/api/clientes/" + cli + "/certificados/a3", "{\"titular\":\"Titular\",\"validoAte\":\"" + ate + "\"}").andExpect(status().isCreated())).get("id").asLong();
    }
    private void procuracao(String t, long cli, LocalDate venc) throws Exception {
        mvc.perform(multipart("/api/clientes/" + cli + "/procuracoes").param("tipo", "ECAC").param("dataOutorga", "2026-01-01").param("dataVencimento", venc.toString())
                .header("Authorization", "Bearer " + t)).andExpect(status().isCreated());
    }
    private JsonNode avisos(String t) throws Exception { return corpo(json, ler(mvc, t, "/api/notificacoes").andExpect(status().isOk())); }

    @Test
    void avisaGestaoUmaVezPorMarcoEVoltaAAvisarNoMarcoSeguinte() throws Exception {
        Empresa e = empresa(mvc, json);
        long cli = cliente(mvc, json, e.gestor(), "30", "Alerta " + e.s(), null);
        long cert = a3(e.gestor(), cli, LocalDate.now().plusDays(20));
        procuracao(e.gestor(), cli, LocalDate.now().plusDays(4));
        alertas.verificar();

        JsonNode g = avisos(e.gestor());
        String todos = g.toString();
        assertThat(todos).contains("Certificado digital A3 vence em 20 dias").contains("Procuração e-CAC vence em 4 dias").contains("Alerta " + e.s());
        assertThat(avisos(e.admin()).toString()).contains("Certificado digital A3 vence em 20 dias");     // administrador também
        assertThat(avisos(e.usuario())).isEmpty();                                                         // usuário não
        assertThat(g.toString()).contains("\"clienteId\":" + cli);                                         // o aviso aponta para a empresa

        int antes = avisos(e.gestor()).size();
        alertas.verificar();
        assertThat(avisos(e.gestor())).hasSize(antes);                                                     // não repete no mesmo marco

        // o certificado entra no marco de 15 dias: novo aviso
        var c = certs.findById(cert).orElseThrow();
        c.setValidoAte(LocalDate.now().plusDays(12));
        certs.save(c);
        alertas.verificar();
        assertThat(avisos(e.gestor())).hasSize(antes + 1);
        assertThat(avisos(e.gestor()).toString()).contains("Certificado digital A3 vence em 12 dias");

        // vencido: aviso próprio
        c.setValidoAte(LocalDate.now().minusDays(2));
        certs.save(c);
        alertas.verificar();
        assertThat(avisos(e.gestor()).toString()).contains("Certificado digital A3 vencido");
    }

    @Test
    void semAvisoQuandoFaltaMaisDeTrintaDiasOuQuandoDesativado() throws Exception {
        Empresa e = empresa(mvc, json);
        long cli = cliente(mvc, json, e.gestor(), "31", "Longe " + e.s(), null);
        a3(e.gestor(), cli, LocalDate.now().plusDays(90));
        long desativado = a3(e.gestor(), cli, LocalDate.now().plusDays(3));
        alterar(mvc, e.gestor(), "/api/clientes/" + cli + "/certificados/" + desativado, "{\"ativo\":false}").andExpect(status().isOk());
        alertas.verificar();
        assertThat(avisos(e.gestor())).isEmpty();
    }

    @Test
    void painelDeSaudeContaVencimentosEPendenciasDeCadastro() throws Exception {
        Empresa e = empresa(mvc, json);
        long completa = cliente(mvc, json, e.gestor(), "32", "Completa " + e.s(), "{\"cnpj\":\"11222333000181\",\"regimeTributario\":\"SIMPLES_NACIONAL\"}");
        long incompleta = cliente(mvc, json, e.gestor(), "33", "Incompleta " + e.s(), null);
        a3(e.gestor(), completa, LocalDate.now().plusDays(3));
        a3(e.gestor(), completa, LocalDate.now().minusDays(1));
        procuracao(e.gestor(), completa, LocalDate.now().plusDays(25));

        JsonNode s = corpo(json, ler(mvc, e.gestor(), "/api/cadastros/saude").andExpect(status().isOk()));
        JsonNode r = s.get("resumo");
        assertThat(r.get("certificadosVencidos").asLong()).isEqualTo(1);
        assertThat(r.get("certificadosAVencer").asLong()).isEqualTo(1);
        assertThat(r.get("procuracoesAVencer").asLong()).isEqualTo(1);
        assertThat(r.get("procuracoesVencidas").asLong()).isZero();
        assertThat(r.get("empresasSemCnpj").asLong()).isEqualTo(1);          // a incompleta
        assertThat(r.get("empresasSemRegime").asLong()).isEqualTo(1);
        assertThat(r.get("empresasSemCertificado").asLong()).isEqualTo(1);   // a incompleta (a completa tem um válido)
        assertThat(r.get("onboardingEmAndamento").asLong()).isEqualTo(2);
        JsonNode itens = s.get("itens");
        assertThat(itens).hasSize(3);
        assertThat(itens.get(0).get("dias").asLong()).isNegative();          // o mais urgente primeiro
        assertThat(itens.get(0).get("gravidade").asText()).isEqualTo("CRITICA");
        assertThat(itens.get(2).get("gravidade").asText()).isEqualTo("ALERTA");
        assertThat(itens.toString()).contains("Completa " + e.s()).doesNotContain("Incompleta");

        ler(mvc, e.usuario(), "/api/cadastros/saude").andExpect(status().isForbidden());
        Empresa outra = empresa(mvc, json);
        assertThat(corpo(json, ler(mvc, outra.admin(), "/api/cadastros/saude")).get("itens")).isEmpty();   // cada empresa vê só o seu
    }
}
