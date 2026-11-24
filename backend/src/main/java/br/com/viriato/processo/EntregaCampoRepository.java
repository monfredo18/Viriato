package br.com.viriato.processo;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EntregaCampoRepository extends JpaRepository<EntregaCampo, Long> {
    List<EntregaCampo> findByEntregaIdAndTenantIdOrderByOrdemAscIdAsc(Long entregaId, Long tenantId);
    void deleteByEntregaIdAndTenantId(Long entregaId, Long tenantId);
}
