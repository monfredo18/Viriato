package br.com.viriato.processo;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessoHistoricoRepository extends JpaRepository<ProcessoHistorico, Long> {
    List<ProcessoHistorico> findByProcessoIdAndTenantIdOrderByIdDesc(Long processoId, Long tenantId);
}
