package br.com.viriato.ged;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DfeControleRepository extends JpaRepository<DfeControle, Long> {
    Optional<DfeControle> findByClienteIdAndTenantId(Long clienteId, Long tenantId);
}
