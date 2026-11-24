package br.com.viriato.crm;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClienteContatoRepository extends JpaRepository<ClienteContato, Long> {
    List<ClienteContato> findByClienteIdAndTenantIdOrderByNome(Long clienteId, Long tenantId);
    Optional<ClienteContato> findByIdAndClienteIdAndTenantId(Long id, Long clienteId, Long tenantId);
}
