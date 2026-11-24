package br.com.viriato.fiscal;

import br.com.viriato.fiscal.Enums.EscopoFeriado;
import br.com.viriato.fiscal.Enums.RegraDiaUtil;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Calendário de uma empresa atendida (UF e município dela): sábado, domingo e feriados nacionais, estaduais e municipais
 * não são dias úteis. A Sexta-feira Santa é calculada a partir da Páscoa. Objeto leve e sem estado externo: monte um por
 * empresa atendida e reaproveite.
 */
public final class CalendarioFiscal {
    private final List<Feriado> feriados;
    private final String uf;
    private final String ibge;
    private final Map<Integer, Set<LocalDate>> porAno = new HashMap<>();

    public CalendarioFiscal(List<Feriado> feriadosDaEmpresa, String uf, String ibge) {
        this.feriados = feriadosDaEmpresa;
        this.uf = uf == null ? null : uf.toUpperCase();
        this.ibge = ibge;
    }

    public boolean ehFeriado(LocalDate d) { return feriadosDoAno(d.getYear()).contains(d); }

    public boolean ehDiaUtil(LocalDate d) {
        DayOfWeek w = d.getDayOfWeek();
        return w != DayOfWeek.SATURDAY && w != DayOfWeek.SUNDAY && !ehFeriado(d);
    }

    /** Aplica a regra legal da obrigação à data de vencimento. */
    public LocalDate ajustar(LocalDate d, RegraDiaUtil regra) {
        return switch (regra) {
            case MANTER_DATA -> d;
            case ANTECIPAR_DIA_UTIL -> { LocalDate x = d; while (!ehDiaUtil(x)) x = x.minusDays(1); yield x; }
            case POSTERGAR_DIA_UTIL -> { LocalDate x = d; while (!ehDiaUtil(x)) x = x.plusDays(1); yield x; }
        };
    }

    /** Soma (ou subtrai, se negativo) dias úteis; a data de partida não conta. */
    public LocalDate somarDiasUteis(LocalDate d, int n) {
        int passo = n < 0 ? -1 : 1;
        int faltam = Math.abs(n);
        LocalDate x = d;
        while (faltam > 0) {
            x = x.plusDays(passo);
            if (ehDiaUtil(x)) faltam--;
        }
        return x;
    }

    /** O n-ésimo dia útil do mês (1 = primeiro). */
    public LocalDate enesimoDiaUtil(int ano, int mes, int n) {
        if (n < 1) throw new IllegalArgumentException("n deve ser 1 ou mais");
        LocalDate x = LocalDate.of(ano, mes, 1);
        int contados = 0;
        while (true) {
            if (ehDiaUtil(x) && ++contados == n) return x;
            x = x.plusDays(1);
        }
    }

    public LocalDate ultimoDiaUtil(int ano, int mes) {
        LocalDate x = YearMonth.of(ano, mes).atEndOfMonth();
        while (!ehDiaUtil(x)) x = x.minusDays(1);
        return x;
    }

    private Set<LocalDate> feriadosDoAno(int ano) {
        return porAno.computeIfAbsent(ano, a -> {
            Set<LocalDate> s = new HashSet<>();
            s.add(pascoa(a).minusDays(2));   // Sexta-feira Santa (feriado nacional)
            for (Feriado f : feriados) {
                if (!f.isAtivo() || !vale(f)) continue;
                if (f.getData() != null) { if (f.getData().getYear() == a) s.add(f.getData()); }
                else if (f.getDia() != null && f.getMes() != null && f.getMes() >= 1 && f.getMes() <= 12 && f.getDia() >= 1 && f.getDia() <= YearMonth.of(a, f.getMes()).lengthOfMonth()) {
                    s.add(LocalDate.of(a, f.getMes(), f.getDia()));
                }
            }
            return s;
        });
    }

    private boolean vale(Feriado f) {
        EscopoFeriado e = f.getEscopo();
        if (e == EscopoFeriado.NACIONAL) return true;
        if (e == EscopoFeriado.ESTADUAL) return uf != null && uf.equalsIgnoreCase(f.getUf());
        return ibge != null && ibge.equals(f.getCodigoMunicipioIbge());
    }

    /** Domingo de Páscoa (algoritmo de Meeus/Jones/Butcher). */
    static LocalDate pascoa(int ano) {
        int a = ano % 19, b = ano / 100, c = ano % 100, d = b / 4, e = b % 4, f = (b + 8) / 25, g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30, i = c / 4, k = c % 4, l = (32 + 2 * e + 2 * i - h - k) % 7, m = (a + 11 * h + 22 * l) / 451;
        int mes = (h + l - 7 * m + 114) / 31, dia = (h + l - 7 * m + 114) % 31 + 1;
        return LocalDate.of(ano, mes, dia);
    }
}
