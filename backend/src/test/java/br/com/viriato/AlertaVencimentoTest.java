package br.com.viriato;

import static br.com.viriato.Crm.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.viriato.crm.AlertaVencimentoService;
import br.com.viriato.crm.ClienteCertificadoRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
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

    /** Certificados "instalados" no computador do gestor: o agente sempre manda a lista completa. */
    private record Maquina(String agente, String gestor) {
        void instalar(MockMvc mvc, String... ders) throws Exception { Agentes.contato(mvc, agente, List.of(ders)).andExpect(status().isOk()); }
    }

    private Maquina maquina(Empresa e) throws Exception { return new Maquina(Agentes.parear(mvc, json, e.gestor(), "PC " + e.s()), e.gestor()); }

    /** Id do certificado (pelo titular) depois de lido pelo agente, ligado à empresa como o gestor faria na tela. */
    private long ligar(String gestor, String titular, long cli) throws Exception {
        for (JsonNode c : corpo(json, ler(mvc, gestor, "/api/certificados").andExpect(status().isOk()))) {
            if (c.get("titular").asText().contains(titular)) {
                alterar(mvc, gestor, "/api/certificados/" + c.get("id").asLong(), "{\"clienteId\":" + cli + "}").andExpect(status().isOk());
                return c.get("id").asLong();
            }
        }
        throw new AssertionError("certificado não encontrado: " + titular);
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
        var pc = maquina(e);
        pc.instalar(mvc, Agentes.der("ALERTA UM " + e.s(), 20));
        long cert = ligar(e.gestor(), "ALERTA UM " + e.s(), cli);
        procuracao(e.gestor(), cli, LocalDate.now().plusDays(4));
        alertas.verificar();

        JsonNode g = avisos(e.gestor());
        String todos = g.toString();
        assertThat(todos).contains("Certificado digital vence em 20 dias").contains("Procuração e-CAC vence em 4 dias").contains("Alerta " + e.s());
        assertThat(avisos(e.admin()).toString()).contains("Certificado digital vence em 20 dias");     // administrador também
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
        assertThat(avisos(e.gestor()).toString()).contains("Certificado digital vence em 12 dias");

        // vencido: aviso próprio
        c.setValidoAte(LocalDate.now().minusDays(2));
        certs.save(c);
        alertas.verificar();
        assertThat(avisos(e.gestor()).toString()).contains("Certificado digital vencido");
    }

    @Test
    void semAvisoQuandoFaltaMaisDeTrintaDiasOuQuandoDesativado() throws Exception {
        Empresa e = empresa(mvc, json);
        long cli = cliente(mvc, json, e.gestor(), "31", "Longe " + e.s(), null);
        var pc = maquina(e);
        String longe = Agentes.der("LONGE " + e.s(), 90);
        pc.instalar(mvc, longe, Agentes.der("PERTO " + e.s(), 3));
        ligar(e.gestor(), "LONGE " + e.s(), cli);
        long perto = ligar(e.gestor(), "PERTO " + e.s(), cli);
        pc.instalar(mvc, longe);                                       // o certificado que vencia logo foi retirado do computador: não há o que avisar
        assertThat(certs.findById(perto).orElseThrow().isAtivo()).isFalse();
        alertas.verificar();
        assertThat(avisos(e.gestor())).isEmpty();
    }

    @Test
    void painelDeSaudeContaVencimentosEPendenciasDeCadastro() throws Exception {
        Empresa e = empresa(mvc, json);
        long completa = cliente(mvc, json, e.gestor(), "32", "Completa " + e.s(), "{\"cnpj\":\"11222333000181\",\"regimeTributario\":\"SIMPLES_NACIONAL\"}");
        long incompleta = cliente(mvc, json, e.gestor(), "33", "Incompleta " + e.s(), null);
        var pc = maquina(e);
        pc.instalar(mvc, Agentes.der("SAUDE UM " + e.s(), 3), Agentes.der("SAUDE DOIS " + e.s(), -1));
        ligar(e.gestor(), "SAUDE UM " + e.s(), completa);
        ligar(e.gestor(), "SAUDE DOIS " + e.s(), completa);
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
