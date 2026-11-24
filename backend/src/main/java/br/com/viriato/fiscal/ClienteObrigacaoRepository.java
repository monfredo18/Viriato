package br.com.viriato.fiscal;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClienteObrigacaoRepository extends JpaRepository<ClienteObrigacao, Long> {
    List<ClienteObrigacao> findByClienteIdAndTenantId(Long clienteId, Long tenantId);
    Optional<ClienteObrigacao> findByClienteIdAndModeloIdAndTenantId(Long clienteId, Long modeloId, Long tenantId);
    List<ClienteObrigacao> findByModeloIdAndTenantIdAndAtivoTrue(Long modeloId, Long tenantId);
    boolean existsByClienteIdAndModeloId(Long clienteId, Long modeloId);
}
