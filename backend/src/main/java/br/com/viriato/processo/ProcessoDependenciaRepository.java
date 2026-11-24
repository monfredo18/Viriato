package br.com.viriato.processo;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessoDependenciaRepository extends JpaRepository<ProcessoDependencia, Long> {
    List<ProcessoDependencia> findByProcessoIdAndTenantId(Long processoId, Long tenantId);
    List<ProcessoDependencia> findByPredecessorIdAndTenantId(Long predecessorId, Long tenantId);
    Optional<ProcessoDependencia> findByIdAndProcessoIdAndTenantId(Long id, Long processoId, Long tenantId);
    boolean existsByProcessoIdAndPredecessorId(Long processoId, Long predecessorId);
}
