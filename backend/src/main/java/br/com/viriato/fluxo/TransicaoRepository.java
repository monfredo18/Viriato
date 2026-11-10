package br.com.viriato.fluxo;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransicaoRepository extends JpaRepository<Transicao, Long> {
    List<Transicao> findByFluxoIdAndTenantId(Long fluxoId, Long tenantId);
    boolean existsByDeEtapaAndParaEtapaAndTenantId(Long de, Long para, Long tenantId);
}
