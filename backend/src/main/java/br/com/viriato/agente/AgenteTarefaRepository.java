package br.com.viriato.agente;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface AgenteTarefaRepository extends JpaRepository<AgenteTarefa, Long> {
    @Query("select t from AgenteTarefa t where t.status = 'PENDENTE' and t.certificadoId in (select ca.certificadoId from CertificadoAgente ca where ca.agenteId = :agente) order by t.id")
    List<AgenteTarefa> pendentesDoAgente(Long agente, Pageable limite);

    /** Reserva a tarefa para um agente; só um consegue (quem chega depois não leva nada). */
    @Modifying
    @Query("update AgenteTarefa t set t.status = 'EM_EXECUCAO', t.agenteId = :agente, t.atendidoEm = :agora where t.id = :id and t.status = 'PENDENTE'")
    int reservar(Long id, Long agente, Instant agora);

    @Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("update AgenteTarefa t set t.status = 'EXPIRADA', t.corpo = null where t.id = :id and t.status in ('PENDENTE', 'EM_EXECUCAO')")
    int expirar(Long id);

    @Modifying
    @Query("delete from AgenteTarefa t where t.criadoEm < :limite")
    int apagarAnteriores(Instant limite);
}
