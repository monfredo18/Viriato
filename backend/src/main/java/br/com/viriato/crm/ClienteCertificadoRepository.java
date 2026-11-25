package br.com.viriato.crm;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ClienteCertificadoRepository extends JpaRepository<ClienteCertificado, Long> {
    List<ClienteCertificado> findByClienteIdAndTenantIdOrderByIdDesc(Long clienteId, Long tenantId);
    List<ClienteCertificado> findByTenantIdOrderByTitular(Long tenantId);
    Optional<ClienteCertificado> findByIdAndTenantId(Long id, Long tenantId);
    Optional<ClienteCertificado> findByTenantIdAndThumbprint(Long tenantId, String thumbprint);
    List<ClienteCertificado> findByTenantIdAndAtivoTrue(Long tenantId);
    List<ClienteCertificado> findByAtivoTrue();

    @Modifying
    @Query("update ClienteCertificado c set c.clienteId = null, c.vinculoManual = false where c.clienteId = :clienteId and c.tenantId = :tenantId")
    int desvincularDaEmpresa(Long tenantId, Long clienteId);
}
