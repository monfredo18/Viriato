package br.com.viriato.automacao;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface AutomacaoExecucaoRepository extends JpaRepository<AutomacaoExecucao, Long> {
    List<AutomacaoExecucao> findTop20ByProcessoIdAndTenantIdOrderByIdDesc(Long processoId, Long tenantId);

    @Query("select x from AutomacaoExecucao x where x.status = br.com.viriato.automacao.StatusExecucao.PENDENTE and x.proximaTentativa <= :agora order by x.id")
    List<AutomacaoExecucao> prontas(Instant agora, Pageable limite);

    /** Reserva a execução para esta instância; devolve 0 se outra já a pegou (duas instâncias nunca rodam a mesma). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update AutomacaoExecucao x set x.status = br.com.viriato.automacao.StatusExecucao.PROCESSANDO, x.iniciadoEm = :agora "
            + "where x.id = :id and x.status = br.com.viriato.automacao.StatusExecucao.PENDENTE")
    int reservar(Long id, Instant agora);

    /** Execução que ficou PROCESSANDO além do limite (o servidor caiu no meio) volta para a fila. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update AutomacaoExecucao x set x.status = br.com.viriato.automacao.StatusExecucao.PENDENTE, x.proximaTentativa = :agora "
            + "where x.status = br.com.viriato.automacao.StatusExecucao.PROCESSANDO and x.iniciadoEm < :limite")
    int devolverTravadas(Instant agora, Instant limite);
}
