package br.com.viriato.crm;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClienteProcuracaoRepository extends JpaRepository<ClienteProcuracao, Long> {
    List<ClienteProcuracao> findByClienteIdAndTenantIdOrderByIdDesc(Long clienteId, Long tenantId);
    Optional<ClienteProcuracao> findByIdAndClienteIdAndTenantId(Long id, Long clienteId, Long tenantId);
    List<ClienteProcuracao> findByTenantIdAndAtivoTrue(Long tenantId);
    List<ClienteProcuracao> findByAtivoTrue();
}
