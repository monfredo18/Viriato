package br.com.viriato.processo;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ProcessoAprovacaoRepository extends JpaRepository<ProcessoAprovacao, Long> {
    List<ProcessoAprovacao> findByProcessoIdAndTenantIdOrderByIdDesc(Long processoId, Long tenantId);
    Optional<ProcessoAprovacao> findByIdAndProcessoIdAndTenantId(Long id, Long processoId, Long tenantId);
    boolean existsByProcessoIdAndStatus(Long processoId, StatusAprovacao status);

    /** O processo andou (ou foi cancelado) por outro caminho: os pedidos pendentes perdem o sentido. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ProcessoAprovacao a set a.status = br.com.viriato.processo.StatusAprovacao.CANCELADA, a.decididoEm = :agora "
            + "where a.processoId = :processoId and a.status = br.com.viriato.processo.StatusAprovacao.PENDENTE")
    int cancelarPendentes(Long processoId, Instant agora);
}
