package br.com.viriato.automacao;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AutomacaoRegraRepository extends JpaRepository<AutomacaoRegra, Long> {
    List<AutomacaoRegra> findByFluxoIdAndTenantIdOrderByIdAsc(Long fluxoId, Long tenantId);
    Optional<AutomacaoRegra> findByIdAndFluxoIdAndTenantId(Long id, Long fluxoId, Long tenantId);

    @Query("select count(r) from AutomacaoRegra r where r.tenantId = :t and r.fluxoId = :f and r.evento = :e "
            + "and ((:etapa is null and r.etapaId is null) or r.etapaId = :etapa)")
    long contar(Long t, Long f, EventoAutomacao e, Long etapa);

    @Query("select count(r) from AutomacaoRegra r where r.tenantId = :t and r.fluxoId = :f and r.evento = :e "
            + "and ((:etapa is null and r.etapaId is null) or r.etapaId = :etapa) and r.tipo = :tipo")
    long contarDoTipo(Long t, Long f, EventoAutomacao e, Long etapa, TipoAutomacao tipo);

    @Query("select r from AutomacaoRegra r where r.tenantId = :t and r.fluxoId = :f and r.evento = :e and r.ativo = true "
            + "and ((:etapa is null and r.etapaId is null) or r.etapaId = :etapa) order by r.id")
    List<AutomacaoRegra> ativas(Long t, Long f, EventoAutomacao e, Long etapa);
}
