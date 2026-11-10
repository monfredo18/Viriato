package br.com.viriato.documento;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentoRepository extends JpaRepository<Documento, Long> {
    List<Documento> findByProcessoIdAndTenantIdOrderByIdDesc(Long processoId, Long tenantId);
    Optional<Documento> findByIdAndTenantId(Long id, Long tenantId);
}
