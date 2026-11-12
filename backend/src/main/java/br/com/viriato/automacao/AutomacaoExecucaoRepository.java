package br.com.viriato.automacao;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AutomacaoExecucaoRepository extends JpaRepository<AutomacaoExecucao, Long> {
    List<AutomacaoExecucao> findTop20ByProcessoIdAndTenantIdOrderByIdDesc(Long processoId, Long tenantId);
}
