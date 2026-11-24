package br.com.viriato;

import static br.com.viriato.Crm.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.viriato.fiscal.CalendarioFiscal;
import br.com.viriato.fiscal.Enums.*;
import br.com.viriato.fiscal.MatrizService;
import br.com.viriato.fiscal.ObrigacaoModelo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** Matriz tributária, vínculo automático por regime/UF/CNAE, geração recorrente idempotente e dependências entre processos. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(Crm.StorageFake.class)
class ObrigacoesTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private static final String PGDAS = "{\"nome\":\"PGDAS-D\",\"periodicidade\":\"MENSAL\",\"modoVencimento\":\"DIA_DO_MES\",\"diaVencimento\":20,\"mesesDefasagem\":1,"
            + "\"mesesAplicaveis\":[1,2,3,4,5,6,7,8,9,10,11,12],\"regraDiaUtil\":\"ANTECIPAR_DIA_UTIL\",\"esfera\":\"FEDERAL\",\"regimesAplicaveis\":[\"SIMPLES_NACIONAL\"]";

    private long entrega(Empresa e) throws Exception { return corpo(json, ler(mvc, e.admin(), "/api/setores")).get(0).get("entregas").get(0).get("id").asLong(); }

    private long modelo(String t, String corpoSemFim, long entrega, String extra) throws Exception {
        return corpo(json, enviar(mvc, t, "/api/obrigacoes/modelos", corpoSemFim + ",\"entregaId\":" + entrega + (extra == null ? "" : "," + extra) + "}").andExpect(status().isCreated())).get("id").asLong();
    }

    private JsonNode obrigacoes(String t, long cliente) throws Exception { return corpo(json, ler(mvc, t, "/api/clientes/" + cliente + "/obrigacoes").andExpect(status().isOk())); }

    @Test
    void vencimentoUsaDefasagemModoERegraDeDiaUtil() {
        // PGDAS-D da competência 10/2026: vence dia 20 de novembro, que é feriado (Consciência Negra) e antecipa para o dia 19
        CalendarioFiscal cal = new CalendarioFiscal(feriadosNacionais(), null, null);
        ObrigacaoModelo pgdas = m(ModoVencimento.DIA_DO_MES, 20, 1, RegraDiaUtil.ANTECIPAR_DIA_UTIL);
        assertThat(MatrizService.vencimento(cal, pgdas, 2026, 10)).isEqualTo(LocalDate.of(2026, 11, 19));
        assertThat(MatrizService.vencimento(cal, m(ModoVencimento.DIA_DO_MES, 20, 1, RegraDiaUtil.POSTERGAR_DIA_UTIL), 2026, 10)).isEqualTo(LocalDate.of(2026, 11, 23));
        assertThat(MatrizService.vencimento(cal, m(ModoVencimento.DIA_DO_MES, 20, 1, RegraDiaUtil.MANTER_DATA), 2026, 10)).isEqualTo(LocalDate.of(2026, 11, 20));
        // dia 31 em mês curto vira o último dia do mês (competência 1/2026 -> fevereiro)
        assertThat(MatrizService.vencimento(cal, m(ModoVencimento.DIA_DO_MES, 31, 1, RegraDiaUtil.MANTER_DATA), 2026, 1)).isEqualTo(LocalDate.of(2026, 2, 28));
        // EFD-Contribuições: 10º dia útil do 2º mês seguinte (competência 10/2026 -> dezembro)
        assertThat(MatrizService.vencimento(cal, m(ModoVencimento.ENESIMO_DIA_UTIL, 10, 2, RegraDiaUtil.MANTER_DATA), 2026, 10)).isEqualTo(LocalDate.of(2026, 12, 14));
        // ECD: último dia útil de maio do ano seguinte (competência 12/2026, defasagem 5)
        assertThat(MatrizService.vencimento(cal, m(ModoVencimento.ULTIMO_DIA_UTIL, null, 5, RegraDiaUtil.MANTER_DATA), 2026, 12)).isEqualTo(LocalDate.of(2027, 5, 31));
    }

    private static List<br.com.viriato.fiscal.Feriado> feriadosNacionais() {
        int[][] d = {{1, 1}, {21, 4}, {1, 5}, {7, 9}, {12, 10}, {2, 11}, {15, 11}, {20, 11}, {25, 12}};
        return java.util.Arrays.stream(d).map(x -> { var f = new br.com.viriato.fiscal.Feriado(); f.setEscopo(EscopoFeriado.NACIONAL); f.setDia(x[0]); f.setMes(x[1]); f.setDescricao("x"); return f; }).toList();
    }

    private static ObrigacaoModelo m(ModoVencimento modo, Integer dia, int defasagem, RegraDiaUtil regra) {
        ObrigacaoModelo o = new ObrigacaoModelo();
        o.setModoVencimento(modo); o.setDiaVencimento(dia); o.setMesesDefasagem(defasagem); o.setRegraDiaUtil(regra);
        return o;
    }

    @Test
    void catalogoModelosPermissoesEValidacoes() throws Exception {
        Empresa e = empresa(mvc, json);
        long entrega = entrega(e);
        assertThat(corpo(json, ler(mvc, e.gestor(), "/api/obrigacoes/catalogo").andExpect(status().isOk())).toString()).contains("PGDAS-D").contains("EFD-Contribuições").contains("ECD");
        ler(mvc, e.usuario(), "/api/obrigacoes/modelos").andExpect(status().isForbidden());
        enviar(mvc, e.usuario(), "/api/obrigacoes/modelos", PGDAS + ",\"entregaId\":" + entrega + "}").andExpect(status().isForbidden());

        enviar(mvc, e.admin(), "/api/obrigacoes/modelos", PGDAS.replace("\"diaVencimento\":20", "\"diaVencimento\":40") + ",\"entregaId\":" + entrega + "}").andExpect(status().isUnprocessableEntity());
        enviar(mvc, e.admin(), "/api/obrigacoes/modelos", PGDAS.replace("[1,2,3,4,5,6,7,8,9,10,11,12]", "[13]") + ",\"entregaId\":" + entrega + "}").andExpect(status().isUnprocessableEntity());
        enviar(mvc, e.admin(), "/api/obrigacoes/modelos", PGDAS + ",\"entregaId\":99999}").andExpect(status().isNotFound());
        enviar(mvc, e.admin(), "/api/obrigacoes/modelos", PGDAS + ",\"ufsAplicaveis\":[\"ZZ\"],\"entregaId\":" + entrega + "}").andExpect(status().isUnprocessableEntity());
        JsonNode criado = corpo(json, enviar(mvc, e.gestor(), "/api/obrigacoes/modelos", PGDAS + ",\"entregaId\":" + entrega + "}").andExpect(status().isCreated()));   // gestor, entrega do próprio setor
        assertThat(criado.get("entregaNome").asText()).isEqualTo("Atendimento geral");
        enviar(mvc, e.gestor(), "/api/obrigacoes/modelos", PGDAS + ",\"entregaId\":" + entrega + "}").andExpect(status().isUnprocessableEntity());   // nome repetido na entrega
        Empresa outra = empresa(mvc, json);
        alterar(mvc, outra.admin(), "/api/obrigacoes/modelos/" + criado.get("id").asLong(), "{\"ativo\":false}").andExpect(status().isNotFound());
        assertThat(corpo(json, ler(mvc, outra.admin(), "/api/obrigacoes/modelos"))).isEmpty();
    }

    @Test
    void vinculaSozinhoPorRegimeUfECnae() throws Exception {
        Empresa e = empresa(mvc, json);
        long entrega = entrega(e);
        long simples = cliente(mvc, json, e.gestor(), "40", "Simples " + e.s(), "{\"regimeTributario\":\"SIMPLES_NACIONAL\",\"uf\":\"SP\",\"cnaePrincipal\":\"6201-5/01\"}");
        long presumido = cliente(mvc, json, e.gestor(), "41", "Presumido " + e.s(), "{\"regimeTributario\":\"LUCRO_PRESUMIDO\",\"uf\":\"RJ\"}");
        long semRegime = cliente(mvc, json, e.gestor(), "42", "Semregime " + e.s(), null);

        long pgdas = modelo(e.gestor(), PGDAS, entrega, null);   // só Simples
        assertThat(obrigacoes(e.gestor(), simples).toString()).contains("\"vinculada\":true");
        assertThat(obrigacoes(e.gestor(), presumido).get(0).get("vinculada").asBoolean()).isFalse();
        assertThat(obrigacoes(e.gestor(), presumido).get(0).get("compativel").asBoolean()).isFalse();
        assertThat(obrigacoes(e.gestor(), semRegime).get(0).get("vinculada").asBoolean()).isFalse();

        // por UF e por CNAE (prefixo)
        long soRj = modelo(e.gestor(), "{\"nome\":\"Só RJ\",\"periodicidade\":\"MENSAL\",\"modoVencimento\":\"ULTIMO_DIA_UTIL\",\"mesesDefasagem\":1,\"mesesAplicaveis\":[1,2,3,4,5,6,7,8,9,10,11,12],\"regraDiaUtil\":\"MANTER_DATA\",\"esfera\":\"ESTADUAL\",\"ufsAplicaveis\":[\"RJ\"]", entrega, null);
        long soTi = modelo(e.gestor(), "{\"nome\":\"Só TI\",\"periodicidade\":\"MENSAL\",\"modoVencimento\":\"ULTIMO_DIA_UTIL\",\"mesesDefasagem\":1,\"mesesAplicaveis\":[1,2,3,4,5,6,7,8,9,10,11,12],\"regraDiaUtil\":\"MANTER_DATA\",\"esfera\":\"FEDERAL\",\"cnaesAplicaveis\":[\"62\"]", entrega, null);
        JsonNode doPresumido = obrigacoes(e.gestor(), presumido), doSimples = obrigacoes(e.gestor(), simples);
        assertThat(vinculada(doPresumido, soRj)).isTrue();
        assertThat(vinculada(doPresumido, soTi)).isFalse();
        assertThat(vinculada(doSimples, soTi)).isTrue();    // CNAE 6201501 começa por 62
        assertThat(vinculada(doSimples, soRj)).isFalse();

        // mudar o regime depois vincula o que passou a ser compatível
        alterar(mvc, e.gestor(), "/api/clientes/" + semRegime, "{\"fiscal\":{\"regimeTributario\":\"SIMPLES_NACIONAL\"}}").andExpect(status().isOk());
        assertThat(vinculada(obrigacoes(e.gestor(), semRegime), pgdas)).isTrue();

        // desligar para a empresa e definir o responsável padrão dela; mudar o regime de novo não reativa o que foi desligado
        long gestorId = corpo(json, ler(mvc, e.gestor(), "/api/me")).get("id").asLong();
        JsonNode v = corpo(json, alterar(mvc, e.gestor(), "/api/clientes/" + simples + "/obrigacoes/" + pgdas, "{\"ativa\":false,\"responsavelId\":" + gestorId + "}").andExpect(status().isOk()));
        assertThat(v.get("ativa").asBoolean()).isFalse();
        assertThat(v.get("responsavelId").asLong()).isEqualTo(gestorId);
        alterar(mvc, e.gestor(), "/api/clientes/" + simples, "{\"fiscal\":{\"uf\":\"MG\"}}").andExpect(status().isOk());
        assertThat(ativa(obrigacoes(e.gestor(), simples), pgdas)).isFalse();
        // vínculo manual de obrigação incompatível
        JsonNode manual = corpo(json, alterar(mvc, e.gestor(), "/api/clientes/" + presumido + "/obrigacoes/" + pgdas, "{\"ativa\":true}").andExpect(status().isOk()));
        assertThat(manual.get("personalizado").asBoolean()).isTrue();
        assertThat(manual.get("compativel").asBoolean()).isFalse();
    }

    private static boolean vinculada(JsonNode lista, long modelo) { for (JsonNode o : lista) if (o.get("modeloId").asLong() == modelo) return o.get("vinculada").asBoolean(); return false; }
    private static boolean ativa(JsonNode lista, long modelo) { for (JsonNode o : lista) if (o.get("modeloId").asLong() == modelo) return o.get("ativa").asBoolean(); return false; }

    private JsonNode gerar(Empresa e, int ano, int mes) throws Exception {
        return corpo(json, enviar(mvc, e.gestor(), "/api/obrigacoes/gerar", "{\"ano\":" + ano + ",\"mes\":" + mes + "}").andExpect(status().isOk()));
    }

    private JsonNode processosDaCompetencia(Empresa e, int ano, int mes) throws Exception {
        return corpo(json, ler(mvc, e.admin(), "/api/processos?competenciaAno=" + ano + "&competenciaMes=" + mes + "&tamanho=100").andExpect(status().isOk())).get("content");
    }

    private static final String ECD = "{\"nome\":\"ECD\",\"periodicidade\":\"ANUAL\",\"modoVencimento\":\"ULTIMO_DIA_UTIL\",\"mesesDefasagem\":5,\"mesesAplicaveis\":[12],\"regraDiaUtil\":\"MANTER_DATA\",\"esfera\":\"FEDERAL\"";

    @Test
    void geraProcessosDaCompetenciaComPrazoDoCalendarioEEhIdempotente() throws Exception {
        Empresa e = empresa(mvc, json);
        long entrega = entrega(e);
        long alfa = cliente(mvc, json, e.gestor(), "50", "Alfa " + e.s(), "{\"regimeTributario\":\"SIMPLES_NACIONAL\",\"uf\":\"SP\"}");
        long beta = cliente(mvc, json, e.gestor(), "51", "Beta " + e.s(), "{\"regimeTributario\":\"SIMPLES_NACIONAL\",\"uf\":\"SP\"}");
        long suspensa = cliente(mvc, json, e.gestor(), "52", "Suspensa " + e.s(), "{\"regimeTributario\":\"SIMPLES_NACIONAL\",\"uf\":\"SP\"}");
        alterar(mvc, e.gestor(), "/api/clientes/" + suspensa, "{\"fiscal\":{\"statusContratual\":\"SUSPENSO\"}}").andExpect(status().isOk());
        long pgdas = modelo(e.gestor(), PGDAS, entrega, null);
        modelo(e.gestor(), ECD, entrega, null);
        alterar(mvc, e.gestor(), "/api/clientes/" + beta + "/obrigacoes/" + pgdas, "{\"ativa\":false}").andExpect(status().isOk());   // a Beta não tem o PGDAS

        // competência 10/2026: só o PGDAS da Alfa (a ECD só existe em 12; a Beta desligou; a Suspensa não gera)
        JsonNode r = gerar(e, 2026, 10);
        assertThat(r.get("gerados").asInt()).as(r.toString()).isEqualTo(1);
        assertThat(r.get("jaExistiam").asInt()).isZero();
        JsonNode ps = processosDaCompetencia(e, 2026, 10);
        assertThat(ps).hasSize(1);
        JsonNode p = ps.get(0);
        assertThat(p.get("clienteNome").asText()).isEqualTo("Alfa " + e.s());
        assertThat(p.get("titulo").asText()).contains("10/2026");
        assertThat(p.get("prazoEtapa").asText()).isEqualTo("2026-11-19");               // dia 20/11 é feriado: antecipa para o dia útil anterior
        assertThat(p.get("competenciaMes").asInt()).isEqualTo(10);
        assertThat(p.get("obrigacaoModeloId").asLong()).isEqualTo(pgdas);
        assertThat(p.get("responsavelNome").asText()).isEqualTo("Gestor " + e.s());      // sem responsável definido: o gestor do setor
        assertThat(p.get("etapaAtual").asText()).isEqualTo("Abertura");
        assertThat(corpo(json, ler(mvc, e.admin(), "/api/processos/" + p.get("id").asLong())).get("historico").toString()).contains("Gerado automaticamente");

        // idempotente: repetir não duplica
        JsonNode de_novo = gerar(e, 2026, 10);
        assertThat(de_novo.get("gerados").asInt()).isZero();
        assertThat(de_novo.get("jaExistiam").asInt()).isEqualTo(1);
        assertThat(processosDaCompetencia(e, 2026, 10)).hasSize(1);

        // competência 12/2026: PGDAS da Alfa + ECD da Alfa e da Beta
        assertThat(gerar(e, 2026, 12).get("gerados").asInt()).isEqualTo(3);
        JsonNode dez = processosDaCompetencia(e, 2026, 12);
        assertThat(dez).hasSize(3);
        assertThat(dez.toString()).contains("\"prazoEtapa\":\"2027-05-31\"").contains("\"prazoEtapa\":\"2027-01-20\"");   // ECD: último dia útil de maio/27; PGDAS: 20/1/27

        // o responsável definido para a empresa vale mais que o gestor do setor
        long usuarioId = corpo(json, ler(mvc, e.usuario(), "/api/me")).get("id").asLong();
        alterar(mvc, e.gestor(), "/api/clientes/" + alfa + "/obrigacoes/" + pgdas, "{\"responsavelId\":" + usuarioId + "}").andExpect(status().isOk());
        gerar(e, 2026, 11);
        assertThat(processosDaCompetencia(e, 2026, 11).get(0).get("responsavelNome").asText()).isEqualTo("Usuario " + e.s());

        // filtro de lista por prazo
        assertThat(corpo(json, ler(mvc, e.admin(), "/api/processos?prazoDe=2026-11-19&prazoAte=2026-11-19&tamanho=50")).get("content")).hasSize(1);
        assertThat(corpo(json, ler(mvc, e.admin(), "/api/processos?prazoDe=2030-01-01&tamanho=50")).get("content")).isEmpty();

        enviar(mvc, e.usuario(), "/api/obrigacoes/gerar", "{}").andExpect(status().isForbidden());
        enviar(mvc, e.gestor(), "/api/obrigacoes/gerar", "{\"ano\":2026,\"mes\":13}").andExpect(status().isUnprocessableEntity());
    }

    @Test
    void entregaInativaNaoGeraEFicaNoRelatorioDoLote() throws Exception {
        Empresa e = empresa(mvc, json);
        long alfa = cliente(mvc, json, e.gestor(), "58", "Alfa " + e.s(), "{\"regimeTributario\":\"SIMPLES_NACIONAL\"}");
        long inativa = corpo(json, enviar(mvc, e.gestor(), "/api/entregas", "{\"nome\":\"Entrega que vai parar\"}").andExpect(status().isCreated())).get("id").asLong();
        modelo(e.gestor(), PGDAS, inativa, null);
        alterar(mvc, e.gestor(), "/api/entregas/" + inativa, "{\"ativo\":false}").andExpect(status().isOk());
        JsonNode r = gerar(e, 2026, 10);
        assertThat(r.get("gerados").asInt()).isZero();
        assertThat(r.get("ignorados").toString()).contains("PGDAS-D").contains("inativa");
    }

    private long idDo(JsonNode processos, long modelo) { for (JsonNode p : processos) if (p.get("obrigacaoModeloId").asLong() == modelo) return p.get("id").asLong(); throw new AssertionError("sem processo do modelo " + modelo); }

    private void concluir(String t, long processo) throws Exception {
        for (int i = 0; i < 10; i++) {
            JsonNode d = corpo(json, ler(mvc, t, "/api/processos/" + processo).andExpect(status().isOk()));
            if (d.get("processo").get("status").asText().equals("CONCLUIDO")) return;
            enviar(mvc, t, "/api/processos/" + processo + "/avancar", "{\"etapaId\":" + d.get("proximasEtapas").get(0).get("id").asLong() + "}").andExpect(status().isOk());
        }
    }

    @Test
    void dependenciasEntreProcessosTravamOAvancoAteOPredecessorConcluir() throws Exception {
        Empresa e = empresa(mvc, json);
        long entrega = entrega(e);
        cliente(mvc, json, e.gestor(), "60", "Dep " + e.s(), "{\"regimeTributario\":\"SIMPLES_NACIONAL\"}");
        long xml = modelo(e.gestor(), PGDAS.replace("PGDAS-D", "Importação de XMLs"), entrega, null);
        long apuracao = modelo(e.gestor(), PGDAS.replace("PGDAS-D", "Apuração fiscal"), entrega, "\"dependeDeModeloIds\":[" + xml + "]");
        long ciclo = apuracao;
        // um modelo não depende de si, nem em ciclo
        alterar(mvc, e.gestor(), "/api/obrigacoes/modelos/" + xml, "{\"dependeDeModeloIds\":[" + xml + "]}").andExpect(status().isUnprocessableEntity());
        alterar(mvc, e.gestor(), "/api/obrigacoes/modelos/" + xml, "{\"dependeDeModeloIds\":[" + ciclo + "]}").andExpect(status().isUnprocessableEntity());

        JsonNode r = gerar(e, 2026, 10);
        assertThat(r.get("gerados").asInt()).isEqualTo(2);
        assertThat(r.get("dependenciasCriadas").asInt()).isEqualTo(1);
        JsonNode ps = processosDaCompetencia(e, 2026, 10);
        long pXml = idDo(ps, xml), pApuracao = idDo(ps, apuracao);

        JsonNode detalhe = corpo(json, ler(mvc, e.admin(), "/api/processos/" + pApuracao));
        assertThat(detalhe.get("dependencias")).hasSize(1);
        assertThat(detalhe.get("dependencias").get(0).get("predecessorId").asLong()).isEqualTo(pXml);
        assertThat(detalhe.get("dependencias").get(0).get("resolvida").asBoolean()).isFalse();
        long etapaSeguinte = detalhe.get("proximasEtapas").get(0).get("id").asLong();
        String erro = corpo(json, enviar(mvc, e.admin(), "/api/processos/" + pApuracao + "/avancar", "{\"etapaId\":" + etapaSeguinte + "}").andExpect(status().isUnprocessableEntity())).get("erro").asText();
        assertThat(erro).contains("depende de outro").contains("Importação de XMLs");

        // o predecessor segue livre; ao concluir, o dependente é liberado
        concluir(e.admin(), pXml);
        assertThat(corpo(json, ler(mvc, e.admin(), "/api/processos/" + pApuracao)).get("dependencias").get(0).get("resolvida").asBoolean()).isTrue();
        enviar(mvc, e.admin(), "/api/processos/" + pApuracao + "/avancar", "{\"etapaId\":" + etapaSeguinte + "}").andExpect(status().isOk());

        // gerar de novo não duplica a dependência
        assertThat(gerar(e, 2026, 10).get("dependenciasCriadas").asInt()).isZero();
    }

    @Test
    void dependenciaManualSoParaGestorEAdminSemCiclosESemOutraEmpresa() throws Exception {
        Empresa e = empresa(mvc, json);
        long entrega = entrega(e);
        long c1 = cliente(mvc, json, e.gestor(), "61", "M1 " + e.s(), null), c2 = cliente(mvc, json, e.gestor(), "62", "M2 " + e.s(), null);
        long usuarioId = corpo(json, ler(mvc, e.usuario(), "/api/me")).get("id").asLong();
        java.util.function.LongFunction<Long> abrir = c -> { try { return corpo(json, enviar(mvc, e.admin(), "/api/processos", "{\"clienteId\":" + c + ",\"entregaId\":" + entrega + ",\"setorId\":" + e.setorId() + ",\"responsavelId\":" + usuarioId + "}").andExpect(status().isCreated())).get("id").asLong(); } catch (Exception x) { throw new RuntimeException(x); } };
        long a = abrir.apply(c1), b = abrir.apply(c2);

        enviar(mvc, e.usuario(), "/api/processos/" + a + "/dependencias", "{\"predecessorId\":" + b + "}").andExpect(status().isForbidden());
        enviar(mvc, e.gestor(), "/api/processos/" + a + "/dependencias", "{\"predecessorId\":" + a + "}").andExpect(status().isUnprocessableEntity());
        JsonNode dep = corpo(json, enviar(mvc, e.gestor(), "/api/processos/" + a + "/dependencias", "{\"predecessorId\":" + b + "}").andExpect(status().isCreated()));
        enviar(mvc, e.gestor(), "/api/processos/" + a + "/dependencias", "{\"predecessorId\":" + b + "}").andExpect(status().isUnprocessableEntity());   // repetida
        enviar(mvc, e.gestor(), "/api/processos/" + b + "/dependencias", "{\"predecessorId\":" + a + "}").andExpect(status().isUnprocessableEntity());   // ciclo
        Empresa outra = empresa(mvc, json);
        enviar(mvc, outra.admin(), "/api/processos/" + a + "/dependencias", "{\"predecessorId\":" + b + "}").andExpect(status().isNotFound());
        mvc.perform(delete("/api/processos/" + a + "/dependencias/" + dep.get("id").asLong()).header("Authorization", "Bearer " + e.gestor())).andExpect(status().isNoContent());
        assertThat(corpo(json, ler(mvc, e.admin(), "/api/processos/" + a)).get("dependencias")).isEmpty();
    }
}
