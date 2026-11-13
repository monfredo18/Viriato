package br.com.viriato.automacao;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "automacao_execucao")
@Getter @Setter
public class AutomacaoExecucao {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "tenant_id") private Long tenantId;
    @Column(name = "processo_id") private Long processoId;
    @Column(name = "regra_id") private Long regraId;
    @Enumerated(EnumType.STRING) private TipoAutomacao tipo;
    @Enumerated(EnumType.STRING) private EventoAutomacao evento;
    @Column(name = "etapa_nome") private String etapaNome;
    @Enumerated(EnumType.STRING) private StatusExecucao status;
    private String mensagem;
    @Column(name = "executado_em") private Instant executadoEm;

    @PrePersist void antesDeInserir() { if (executadoEm == null) executadoEm = Instant.now(); }
}
