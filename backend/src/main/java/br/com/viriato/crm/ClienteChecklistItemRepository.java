package br.com.viriato.crm;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClienteChecklistItemRepository extends JpaRepository<ClienteChecklistItem, Long> {
    List<ClienteChecklistItem> findByClienteIdAndTenantIdOrderByTipoAscOrdemAsc(Long clienteId, Long tenantId);
    boolean existsByClienteIdAndTipo(Long clienteId, Enums.TipoChecklist tipo);
    List<ClienteChecklistItem> findByClienteIdAndTenantIdAndTipo(Long clienteId, Long tenantId, Enums.TipoChecklist tipo);
    Optional<ClienteChecklistItem> findByIdAndClienteIdAndTenantId(Long id, Long clienteId, Long tenantId);
    long countByTenantIdAndTipoAndConcluidoFalse(Long tenantId, Enums.TipoChecklist tipo);
}
