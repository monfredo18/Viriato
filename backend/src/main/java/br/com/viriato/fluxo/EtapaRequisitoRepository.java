package br.com.viriato.fluxo;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EtapaRequisitoRepository extends JpaRepository<EtapaRequisito, Long> {
    List<EtapaRequisito> findByEtapaIdAndTenantIdOrderByOrdemAscIdAsc(Long etapaId, Long tenantId);
    List<EtapaRequisito> findByEtapaIdInAndTenantIdOrderByOrdemAscIdAsc(Collection<Long> etapaIds, Long tenantId);
    Optional<EtapaRequisito> findByIdAndTenantId(Long id, Long tenantId);
    long countByEtapaIdAndTenantId(Long etapaId, Long tenantId);
}
