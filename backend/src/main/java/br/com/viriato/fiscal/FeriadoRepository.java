package br.com.viriato.fiscal;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FeriadoRepository extends JpaRepository<Feriado, Long> {
    List<Feriado> findByTenantIdOrderByMesAscDiaAscDataAsc(Long tenantId);
    List<Feriado> findByTenantIdAndAtivoTrue(Long tenantId);
    Optional<Feriado> findByIdAndTenantId(Long id, Long tenantId);
    long countByTenantId(Long tenantId);
}
