package br.com.viriato.processo;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessoRequisitoRepository extends JpaRepository<ProcessoRequisito, Long> {
    List<ProcessoRequisito> findByProcessoIdAndTenantId(Long processoId, Long tenantId);
    Optional<ProcessoRequisito> findByProcessoIdAndRequisitoId(Long processoId, Long requisitoId);
}
