package br.com.viriato.processo;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ProcessoHistoricoRepository extends JpaRepository<ProcessoHistorico, Long> {
    List<ProcessoHistorico> findByProcessoIdAndTenantIdOrderByIdDesc(Long processoId, Long tenantId);

    /** Entradas em etapa (criação e movimentações), na ordem em que ocorreram, para medir o tempo em cada etapa. */
    @Query("select h from ProcessoHistorico h where h.tenantId = :t and h.acao in ('CRIADO', 'MOVIDO') "
            + "and h.processoId in :ids order by h.processoId, h.id")
    List<ProcessoHistorico> entradasEmEtapa(Long t, Collection<Long> ids);
}
