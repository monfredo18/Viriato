package br.com.viriato.agente;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgenteRepository extends JpaRepository<Agente, Long> {
    Optional<Agente> findByTokenHashAndRevogadoFalse(String tokenHash);
    List<Agente> findByTenantIdAndRevogadoFalseOrderByNome(Long tenantId);
    List<Agente> findByTenantIdAndUsuarioIdAndRevogadoFalseOrderByNome(Long tenantId, Long usuarioId);
    Optional<Agente> findByIdAndTenantId(Long id, Long tenantId);
}
