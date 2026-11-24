package br.com.viriato;

import static br.com.viriato.Crm.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.viriato.fiscal.CalendarioFiscal;
import br.com.viriato.fiscal.Enums.EscopoFeriado;
import br.com.viriato.fiscal.Enums.RegraDiaUtil;
import br.com.viriato.fiscal.Feriado;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;

/** Dias úteis, feriados (nacionais, estaduais e municipais) e as três regras de ajuste de vencimento. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(Crm.StorageFake.class)
class CalendarioFiscalTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private static Feriado fixo(EscopoFeriado e, int dia, int mes, String uf, String ibge) {
        Feriado f = new Feriado();
        f.setEscopo(e); f.setDia(dia); f.setMes(mes); f.setUf(uf); f.setCodigoMunicipioIbge(ibge); f.setDescricao("x");
        return f;
    }

    private static CalendarioFiscal nacional(String uf, String ibge, Feriado... extras) {
        java.util.ArrayList<Feriado> l = new java.util.ArrayList<>(List.of(fixo(EscopoFeriado.NACIONAL, 15, 11, null, null), fixo(EscopoFeriado.NACIONAL, 20, 11, null, null),
                fixo(EscopoFeriado.NACIONAL, 1, 5, null, null), fixo(EscopoFeriado.NACIONAL, 21, 4, null, null)));
        l.addAll(List.of(extras));
        return new CalendarioFiscal(l, uf, ibge);
    }

    @Test
    void diasUteisSemanaFeriadoESextaFeiraSanta() {
        CalendarioFiscal c = nacional(null, null);
        assertThat(c.ehDiaUtil(LocalDate.of(2026, 11, 13))).isTrue();     // sexta
        assertThat(c.ehDiaUtil(LocalDate.of(2026, 11, 14))).isFalse();    // sábado
        assertThat(c.ehDiaUtil(LocalDate.of(2026, 11, 15))).isFalse();    // domingo e feriado
        assertThat(c.ehDiaUtil(LocalDate.of(2026, 11, 20))).isFalse();    // Consciência Negra (sexta)
        assertThat(c.ehFeriado(LocalDate.of(2026, 4, 3))).isTrue();       // Sexta-feira Santa, calculada da Páscoa (5/4/2026)
        assertThat(c.ehFeriado(LocalDate.of(2027, 3, 26))).isTrue();      // Sexta-feira Santa de 2027 (Páscoa em 28/3)
        assertThat(c.ehFeriado(LocalDate.of(2026, 4, 2))).isFalse();
    }

    @Test
    void asTresRegrasDeAjuste() {
        CalendarioFiscal c = nacional(null, null);
        LocalDate domingoFeriado = LocalDate.of(2026, 11, 15);
        assertThat(c.ajustar(domingoFeriado, RegraDiaUtil.MANTER_DATA)).isEqualTo(domingoFeriado);
        assertThat(c.ajustar(domingoFeriado, RegraDiaUtil.ANTECIPAR_DIA_UTIL)).isEqualTo(LocalDate.of(2026, 11, 13));   // sexta anterior
        assertThat(c.ajustar(domingoFeriado, RegraDiaUtil.POSTERGAR_DIA_UTIL)).isEqualTo(LocalDate.of(2026, 11, 16));   // segunda seguinte
        // feriado de semana: 20/11/2026 (sexta)
        assertThat(c.ajustar(LocalDate.of(2026, 11, 20), RegraDiaUtil.ANTECIPAR_DIA_UTIL)).isEqualTo(LocalDate.of(2026, 11, 19));
        assertThat(c.ajustar(LocalDate.of(2026, 11, 20), RegraDiaUtil.POSTERGAR_DIA_UTIL)).isEqualTo(LocalDate.of(2026, 11, 23));   // pula o fim de semana
        assertThat(c.ajustar(LocalDate.of(2026, 11, 19), RegraDiaUtil.ANTECIPAR_DIA_UTIL)).isEqualTo(LocalDate.of(2026, 11, 19));  // já é útil
    }

    @Test
    void somaEnesimoEUltimoDiaUtil() {
        CalendarioFiscal c = nacional(null, null);
        assertThat(c.somarDiasUteis(LocalDate.of(2026, 4, 1), 3)).isEqualTo(LocalDate.of(2026, 4, 7));    // pula a Sexta-feira Santa e o fim de semana
        assertThat(c.somarDiasUteis(LocalDate.of(2026, 4, 7), -3)).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(c.enesimoDiaUtil(2026, 5, 5)).isEqualTo(LocalDate.of(2026, 5, 8));                     // 1º de maio é feriado
        assertThat(c.ultimoDiaUtil(2026, 5)).isEqualTo(LocalDate.of(2026, 5, 29));                         // 31/5 é domingo
        assertThat(c.ultimoDiaUtil(2026, 2)).isEqualTo(LocalDate.of(2026, 2, 27));
    }

    @Test
    void feriadoEstadualEMunicipalSoValemNaUfENoMunicipioDaEmpresa() {
        Feriado sp = fixo(EscopoFeriado.ESTADUAL, 9, 7, "SP", null);
        Feriado santos = fixo(EscopoFeriado.MUNICIPAL, 26, 1, null, "3548500");
        LocalDate nove = LocalDate.of(2026, 7, 9), vinteSeis = LocalDate.of(2026, 1, 26);
        assertThat(nacional("SP", "3550308", sp, santos).ehDiaUtil(nove)).isFalse();
        assertThat(nacional("RJ", "3304557", sp, santos).ehDiaUtil(nove)).isTrue();
        assertThat(nacional("SP", "3548500", sp, santos).ehDiaUtil(vinteSeis)).isFalse();     // Santos
        assertThat(nacional("SP", "3550308", sp, santos).ehDiaUtil(vinteSeis)).isTrue();      // São Paulo
        Feriado ano = new Feriado(); ano.setEscopo(EscopoFeriado.NACIONAL); ano.setData(LocalDate.of(2026, 6, 10)); ano.setDescricao("Pontual");
        assertThat(nacional(null, null, ano).ehDiaUtil(LocalDate.of(2026, 6, 10))).isFalse();
        assertThat(nacional(null, null, ano).ehDiaUtil(LocalDate.of(2027, 6, 10))).isTrue();   // data exata vale só no ano dela
    }

    @Test
    void empresaNovaNasceComOsFeriadosNacionaisEOsFeriadosSaoGeridosPorGestorEAdmin() throws Exception {
        Empresa e = empresa(mvc, json);
        JsonNode lista = corpo(json, ler(mvc, e.usuario(), "/api/feriados").andExpect(status().isOk()));   // todos consultam
        assertThat(lista).hasSize(9);
        assertThat(lista.toString()).contains("Tiradentes").contains("Natal").contains("Consciência Negra");

        enviar(mvc, e.usuario(), "/api/feriados", "{\"escopo\":\"ESTADUAL\",\"uf\":\"SP\",\"dia\":9,\"mes\":7,\"descricao\":\"Revolução\"}").andExpect(status().isForbidden());
        enviar(mvc, e.gestor(), "/api/feriados", "{\"escopo\":\"ESTADUAL\",\"uf\":\"XX\",\"dia\":9,\"mes\":7,\"descricao\":\"x\"}").andExpect(status().isUnprocessableEntity());
        enviar(mvc, e.gestor(), "/api/feriados", "{\"escopo\":\"MUNICIPAL\",\"dia\":26,\"mes\":1,\"descricao\":\"x\"}").andExpect(status().isUnprocessableEntity());   // falta o IBGE
        enviar(mvc, e.gestor(), "/api/feriados", "{\"escopo\":\"NACIONAL\",\"descricao\":\"x\"}").andExpect(status().isUnprocessableEntity());   // sem data
        JsonNode novo = corpo(json, enviar(mvc, e.gestor(), "/api/feriados", "{\"escopo\":\"ESTADUAL\",\"uf\":\"sp\",\"dia\":9,\"mes\":7,\"descricao\":\"Revolução Constitucionalista\"}").andExpect(status().isCreated()));
        assertThat(novo.get("uf").asText()).isEqualTo("SP");
        alterar(mvc, e.admin(), "/api/feriados/" + novo.get("id").asLong(), "{\"descricao\":\"9 de Julho\"}").andExpect(status().isOk());
        assertThat(corpo(json, ler(mvc, e.admin(), "/api/feriados"))).hasSize(10);
        mvc.perform(delete("/api/feriados/" + novo.get("id").asLong()).header("Authorization", "Bearer " + e.admin())).andExpect(status().isNoContent());

        Empresa outra = empresa(mvc, json);
        assertThat(corpo(json, ler(mvc, outra.admin(), "/api/feriados"))).hasSize(9);     // cada empresa do sistema tem o seu calendário
        mvc.perform(delete("/api/feriados/" + lista.get(0).get("id").asLong()).header("Authorization", "Bearer " + outra.admin())).andExpect(status().isNotFound());
    }
}
