package br.com.viriato.processo;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface ProcessoRepository extends JpaRepository<Processo, Long>, JpaSpecificationExecutor<Processo> {
    Optional<Processo> findByIdAndTenantId(Long id, Long tenantId);

    // Quantos processos usam cada cadastro (para impedir apagar o que está em uso).
    long countByTenantIdAndClienteId(Long tenantId, Long clienteId);
    long countByTenantIdAndSetorId(Long tenantId, Long setorId);
    long countByTenantIdAndEntregaId(Long tenantId, Long entregaId);
    long countByTenantIdAndResponsavelId(Long tenantId, Long responsavelId);

    /** Usado pelo agendador (varredura global de todos os tenants). */
    @Query("select p from Processo p where p.status = 'ABERTO' and p.prazoAlertado = false "
            + "and p.prazoEtapa is not null and p.prazoEtapa <= :limite and p.responsavelId is not null")
    List<Processo> pendentesDeAlerta(LocalDate limite);
}
