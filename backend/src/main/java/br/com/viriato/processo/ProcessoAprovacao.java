package br.com.viriato.processo;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "processo_aprovacao")
@Getter @Setter
public class ProcessoAprovacao {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "tenant_id") private Long tenantId;
    @Column(name = "processo_id") private Long processoId;
    @Column(name = "etapa_origem_id") private Long etapaOrigemId;
    @Column(name = "etapa_destino_id") private Long etapaDestinoId;
    @Column(name = "solicitado_por") private Long solicitadoPor;
    @Column(name = "solicitado_em") private Instant solicitadoEm;
    private String comentario;
    @Enumerated(EnumType.STRING) private StatusAprovacao status = StatusAprovacao.PENDENTE;
    @Column(name = "decidido_por") private Long decididoPor;
    @Column(name = "decidido_em") private Instant decididoEm;
    private String motivo;

    @PrePersist void antesDeInserir() { if (solicitadoEm == null) solicitadoEm = Instant.now(); }
}
