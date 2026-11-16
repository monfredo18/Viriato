package br.com.viriato.auditoria;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditoriaRepository extends JpaRepository<Auditoria, Long> {
    List<Auditoria> findByTenantIdOrderByIdDesc(Long tenantId, Pageable page);
}
