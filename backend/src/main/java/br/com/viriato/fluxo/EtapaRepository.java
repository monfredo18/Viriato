package br.com.viriato.fluxo;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EtapaRepository extends JpaRepository<Etapa, Long> {
    List<Etapa> findByFluxoIdAndTenantIdOrderByOrdem(Long fluxoId, Long tenantId);
    Optional<Etapa> findByIdAndTenantId(Long id, Long tenantId);
    Optional<Etapa> findFirstByFluxoIdAndTenantIdAndInicialTrue(Long fluxoId, Long tenantId);
}
