package br.com.viriato.fiscal;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ObrigacaoModeloDependenciaRepository extends JpaRepository<ObrigacaoModeloDependencia, Long> {
    List<ObrigacaoModeloDependencia> findByModeloId(Long modeloId);
    List<ObrigacaoModeloDependencia> findByTenantId(Long tenantId);
    void deleteByModeloId(Long modeloId);
}
