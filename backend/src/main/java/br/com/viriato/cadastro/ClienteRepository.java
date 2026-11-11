package br.com.viriato.cadastro;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClienteRepository extends JpaRepository<Cliente, Long> {
    List<Cliente> findByTenantIdOrderByNome(Long tenantId);
    Optional<Cliente> findByIdAndTenantId(Long id, Long tenantId);
    boolean existsByTenantIdAndNomeIgnoreCase(Long tenantId, String nome);
}
