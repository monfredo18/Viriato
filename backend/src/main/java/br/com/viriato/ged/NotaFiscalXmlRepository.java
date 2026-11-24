package br.com.viriato.ged;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface NotaFiscalXmlRepository extends JpaRepository<NotaFiscalXml, Long> {
    boolean existsByTenantIdAndChaveAcesso(Long tenantId, String chaveAcesso);

    @Query("select n from NotaFiscalXml n where n.tenantId = :t and n.clienteId = :c and (:ano is null or n.competenciaAno = :ano) "
            + "and (:mes is null or n.competenciaMes = :mes) and (:tipo is null or n.tipoOperacao = :tipo) order by n.dataEmissao desc, n.id desc")
    List<NotaFiscalXml> buscar(Long t, Long c, Integer ano, Integer mes, String tipo, org.springframework.data.domain.Pageable limite);
}
