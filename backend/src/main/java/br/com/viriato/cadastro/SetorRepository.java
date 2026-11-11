package br.com.viriato.cadastro;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SetorRepository extends JpaRepository<Setor, Long> {
    List<Setor> findByTenantIdOrderByNome(Long tenantId);
    Optional<Setor> findByIdAndTenantId(Long id, Long tenantId);
    boolean existsByTenantIdAndNomeIgnoreCase(Long tenantId, String nome);
}
