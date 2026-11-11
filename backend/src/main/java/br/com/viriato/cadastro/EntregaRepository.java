package br.com.viriato.cadastro;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EntregaRepository extends JpaRepository<Entrega, Long> {
    List<Entrega> findByTenantIdOrderByNome(Long tenantId);
    List<Entrega> findByIdInAndTenantId(Collection<Long> ids, Long tenantId);
    Optional<Entrega> findByIdAndTenantId(Long id, Long tenantId);
    boolean existsByTenantIdAndNomeIgnoreCase(Long tenantId, String nome);
}
