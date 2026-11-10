package br.com.viriato.fluxo;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FluxoRepository extends JpaRepository<Fluxo, Long> {
    List<Fluxo> findByTenantIdOrderByNome(Long tenantId);
    Optional<Fluxo> findByIdAndTenantId(Long id, Long tenantId);
}
