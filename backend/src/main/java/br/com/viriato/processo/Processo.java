package br.com.viriato.processo;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "processo")
@Getter @Setter
public class Processo {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "tenant_id") private Long tenantId;
    private String numero;
    private String titulo;
    private String descricao;
    @Column(name = "fluxo_id") private Long fluxoId;
    @Column(name = "cliente_id") private Long clienteId;
    @Column(name = "entrega_id") private Long entregaId;
    @Column(name = "setor_id") private Long setorId;
    @Column(name = "etapa_atual_id") private Long etapaAtualId;
    @Column(name = "responsavel_id") private Long responsavelId;
    /** Nome de quem era o responsável e foi apagado; marca o processo como restrito ao gestor do setor. */
    @Column(name = "responsavel_removido_nome") private String responsavelRemovidoNome;
    @Enumerated(EnumType.STRING) private StatusProcesso status = StatusProcesso.ABERTO;
    @Column(name = "prazo_etapa") private LocalDate prazoEtapa;
    /** Quando entrou na etapa atual (requisitos só valem se cumpridos nesta passagem). */
    @Column(name = "etapa_desde") private Instant etapaDesde;
    @Column(name = "prazo_alertado") private boolean prazoAlertado;
    @Column(name = "campos_extras") private String camposExtras;
    @Column(name = "criado_por") private Long criadoPor;
    @Column(name = "criado_em") private Instant criadoEm;
    @Column(name = "atualizado_em") private Instant atualizadoEm;

    @PrePersist void antesDeInserir() { criadoEm = Instant.now(); atualizadoEm = criadoEm; }
    @PreUpdate void antesDeAtualizar() { atualizadoEm = Instant.now(); }
}
