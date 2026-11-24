package br.com.viriato.crm;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClienteSocioRepository extends JpaRepository<ClienteSocio, Long> {
    List<ClienteSocio> findByClienteIdAndTenantIdOrderByNome(Long clienteId, Long tenantId);
    Optional<ClienteSocio> findByIdAndClienteIdAndTenantId(Long id, Long clienteId, Long tenantId);
}
