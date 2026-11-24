package br.com.viriato.fiscal;

import br.com.viriato.comum.NaoEncontradoException;
import br.com.viriato.comum.NegocioException;
import br.com.viriato.fiscal.Enums.EscopoFeriado;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Feriados da empresa e o cálculo de dias úteis. A Sexta-feira Santa é sempre considerada (calculada, não cadastrada). */
@Service
public class CalendarioFiscalService {
    static final int[][] NACIONAIS = {{1, 1}, {21, 4}, {1, 5}, {7, 9}, {12, 10}, {2, 11}, {15, 11}, {20, 11}, {25, 12}};
    static final String[] NOMES = {"Confraternização Universal", "Tiradentes", "Dia do Trabalhador", "Independência do Brasil", "Nossa Senhora Aparecida", "Finados",
            "Proclamação da República", "Consciência Negra", "Natal"};
    static final Set<String> UFS = Set.of("AC", "AL", "AP", "AM", "BA", "CE", "DF", "ES", "GO", "MA", "MT", "MS", "MG", "PA", "PB", "PR", "PE", "PI", "RJ", "RN", "RS", "RO", "RR", "SC", "SP", "SE", "TO");

    public record FeriadoDto(Long id, EscopoFeriado escopo, String uf, String codigoMunicipioIbge, LocalDate data, Integer dia, Integer mes, String descricao, boolean ativo) {}
    public record FeriadoReq(EscopoFeriado escopo, String uf, String codigoMunicipioIbge, LocalDate data, Integer dia, Integer mes, String descricao, Boolean ativo) {}

    private final FeriadoRepository repo;

    public CalendarioFiscalService(FeriadoRepository repo) { this.repo = repo; }

    /** Calendário para uma empresa atendida (use a UF e o município dela). */
    @Transactional(readOnly = true)
    public CalendarioFiscal calendario(Long tenantId, String uf, String ibge) {
        return new CalendarioFiscal(repo.findByTenantIdAndAtivoTrue(tenantId), uf, ibge);
    }

    /** Feriados nacionais fixos, para toda empresa nova (as que já existiam recebem na migração). */
    @Transactional
    public void semearNacionais(Long tenantId) {
        if (repo.countByTenantId(tenantId) > 0) return;
        for (int i = 0; i < NACIONAIS.length; i++) {
            Feriado f = new Feriado();
            f.setTenantId(tenantId);
            f.setEscopo(EscopoFeriado.NACIONAL);
            f.setDia(NACIONAIS[i][0]);
            f.setMes(NACIONAIS[i][1]);
            f.setDescricao(NOMES[i]);
            repo.save(f);
        }
    }

    @Transactional(readOnly = true)
    public List<FeriadoDto> listar(Long tenantId) { return repo.findByTenantIdOrderByMesAscDiaAscDataAsc(tenantId).stream().map(CalendarioFiscalService::dto).toList(); }

    @Transactional
    public FeriadoDto criar(Long tenantId, FeriadoReq r) {
        Feriado f = new Feriado();
        f.setTenantId(tenantId);
        aplicar(f, r, true);
        return dto(repo.save(f));
    }

    @Transactional
    public FeriadoDto alterar(Long tenantId, Long id, FeriadoReq r) {
        Feriado f = repo.findByIdAndTenantId(id, tenantId).orElseThrow(() -> new NaoEncontradoException("Feriado"));
        aplicar(f, r, false);
        return dto(repo.save(f));
    }

    @Transactional
    public void apagar(Long tenantId, Long id) {
        repo.delete(repo.findByIdAndTenantId(id, tenantId).orElseThrow(() -> new NaoEncontradoException("Feriado")));
    }

    private void aplicar(Feriado f, FeriadoReq r, boolean novo) {
        if (r.escopo() != null) f.setEscopo(r.escopo());
        if (f.getEscopo() == null) throw new NegocioException("Escolha o escopo do feriado (nacional, estadual ou municipal)");
        if (r.uf() != null) f.setUf(r.uf().isBlank() ? null : r.uf().trim().toUpperCase());
        if (r.codigoMunicipioIbge() != null) f.setCodigoMunicipioIbge(r.codigoMunicipioIbge().isBlank() ? null : r.codigoMunicipioIbge().replaceAll("\\D", ""));
        if (r.data() != null) { f.setData(r.data()); f.setDia(null); f.setMes(null); }
        else if (r.dia() != null || r.mes() != null) { f.setDia(r.dia()); f.setMes(r.mes()); f.setData(null); }
        if (r.descricao() != null) f.setDescricao(r.descricao().trim());
        if (r.ativo() != null) f.setAtivo(r.ativo());
        if (f.getDescricao() == null || f.getDescricao().isBlank() || f.getDescricao().length() > 150) throw new NegocioException("Informe a descrição do feriado (até 150 caracteres)");
        if (f.getData() == null && (f.getDia() == null || f.getMes() == null)) throw new NegocioException("Informe a data (ou o dia e o mês de um feriado fixo)");
        if (f.getData() == null && (f.getMes() < 1 || f.getMes() > 12 || f.getDia() < 1 || f.getDia() > 31)) throw new NegocioException("Dia ou mês inválido");
        if (f.getEscopo() == EscopoFeriado.ESTADUAL && (f.getUf() == null || !UFS.contains(f.getUf()))) throw new NegocioException("Feriado estadual precisa de uma UF válida");
        if (f.getEscopo() == EscopoFeriado.MUNICIPAL && (f.getCodigoMunicipioIbge() == null || f.getCodigoMunicipioIbge().length() != 7)) throw new NegocioException("Feriado municipal precisa do código IBGE do município (7 dígitos)");
        if (f.getEscopo() == EscopoFeriado.NACIONAL) { f.setUf(null); f.setCodigoMunicipioIbge(null); }
    }

    static FeriadoDto dto(Feriado f) { return new FeriadoDto(f.getId(), f.getEscopo(), f.getUf(), f.getCodigoMunicipioIbge(), f.getData(), f.getDia(), f.getMes(), f.getDescricao(), f.isAtivo()); }
}
