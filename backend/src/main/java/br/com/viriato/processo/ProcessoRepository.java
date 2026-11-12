package br.com.viriato.processo;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface ProcessoRepository extends JpaRepository<Processo, Long>, JpaSpecificationExecutor<Processo> {
    Optional<Processo> findByIdAndTenantId(Long id, Long tenantId);

    // Processos EM ANDAMENTO que usam cada cadastro: só eles impedem apagar o cadastro.
    long countByTenantIdAndClienteIdAndStatus(Long tenantId, Long clienteId, StatusProcesso status);
    long countByTenantIdAndSetorIdAndStatus(Long tenantId, Long setorId, StatusProcesso status);
    long countByTenantIdAndEntregaIdAndStatus(Long tenantId, Long entregaId, StatusProcesso status);
    long countByTenantIdAndResponsavelIdAndStatus(Long tenantId, Long responsavelId, StatusProcesso status);
    long countByTenantIdAndFluxoIdAndStatus(Long tenantId, Long fluxoId, StatusProcesso status);

    // O número do processo é o número da empresa: ao editar o número da empresa, os processos acompanham.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Processo p set p.numero = :numero where p.tenantId = :t and p.clienteId = :id")
    int atualizarNumeroDaEmpresa(Long t, Long id, String numero);

    // Ao apagar um cadastro, os processos já encerrados continuam existindo, só sem o vínculo.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Processo p set p.clienteId = null where p.tenantId = :t and p.clienteId = :id")
    int desvincularCliente(Long t, Long id);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Processo p set p.setorId = null where p.tenantId = :t and p.setorId = :id")
    int desvincularSetor(Long t, Long id);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Processo p set p.entregaId = null where p.tenantId = :t and p.entregaId = :id")
    int desvincularEntrega(Long t, Long id);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Processo p set p.fluxoId = null, p.etapaAtualId = null where p.tenantId = :t and p.fluxoId = :id")
    int desvincularFluxo(Long t, Long id);

    long countByTenantIdAndResponsavelIdAndStatusAndPrazoEtapaBefore(Long tenantId, Long responsavelId, StatusProcesso status, LocalDate antes);
    List<Processo> findByTenantIdAndResponsavelIdAndStatusOrderById(Long tenantId, Long responsavelId, StatusProcesso status);

    // Ao apagar um funcionário, os processos encerrados dele guardam o nome (e ficam restritos ao gestor do setor).
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Processo p set p.responsavelId = null, p.responsavelRemovidoNome = :nome where p.tenantId = :t and p.responsavelId = :id")
    int desvincularResponsavel(Long t, Long id, String nome);

    /** Painel de indicadores: todos os em andamento mais os encerrados desde {@code desde}. */
    @Query("select new br.com.viriato.indicador.LinhaProcesso(p.id, p.setorId, p.responsavelId, p.status, p.prazoEtapa, "
            + "p.criadoEm, p.atualizadoEm, p.fluxoId) from Processo p where p.tenantId = :t "
            + "and (:setor is null or p.setorId = :setor) "
            + "and (p.status = br.com.viriato.processo.StatusProcesso.ABERTO or p.atualizadoEm >= :desde) "
            + "and (:semRestritos = false or p.responsavelRemovidoNome is null)")
    List<br.com.viriato.indicador.LinhaProcesso> paraIndicadores(Long t, Long setor, java.time.Instant desde, boolean semRestritos);

    /** Usado pelo agendador (varredura global de todos os tenants). */
    @Query("select p from Processo p where p.status = 'ABERTO' and p.prazoAlertado = false "
            + "and p.prazoEtapa is not null and p.prazoEtapa <= :limite and p.responsavelId is not null")
    List<Processo> pendentesDeAlerta(LocalDate limite);
}
