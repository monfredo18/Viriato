package br.com.viriato.crm;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClienteCertificadoRepository extends JpaRepository<ClienteCertificado, Long> {
    List<ClienteCertificado> findByClienteIdAndTenantIdOrderByIdDesc(Long clienteId, Long tenantId);
    Optional<ClienteCertificado> findByIdAndClienteIdAndTenantId(Long id, Long clienteId, Long tenantId);
    List<ClienteCertificado> findByTenantIdAndAtivoTrue(Long tenantId);
    List<ClienteCertificado> findByAtivoTrue();
}
