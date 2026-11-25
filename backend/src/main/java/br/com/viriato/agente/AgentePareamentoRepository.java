package br.com.viriato.agente;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface AgentePareamentoRepository extends JpaRepository<AgentePareamento, Long> {
    Optional<AgentePareamento> findByCodigoHash(String codigoHash);

    /** Consome o código de uma vez só (duas tentativas ao mesmo tempo não o usam duas vezes). */
    @Modifying
    @Query("update AgentePareamento p set p.usado = true where p.id = :id and p.usado = false")
    int consumir(Long id);

    @Modifying
    @Query("delete from AgentePareamento p where p.expiraEm < :limite")
    int apagarAnteriores(Instant limite);
}
