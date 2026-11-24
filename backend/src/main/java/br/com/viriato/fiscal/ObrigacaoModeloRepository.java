package br.com.viriato.fiscal;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ObrigacaoModeloRepository extends JpaRepository<ObrigacaoModelo, Long> {
    List<ObrigacaoModelo> findByTenantIdOrderByNome(Long tenantId);
    List<ObrigacaoModelo> findByTenantIdAndAtivoTrueOrderByNome(Long tenantId);
    Optional<ObrigacaoModelo> findByIdAndTenantId(Long id, Long tenantId);
    boolean existsByTenantIdAndEntregaIdAndNomeIgnoreCase(Long tenantId, Long entregaId, String nome);
}
