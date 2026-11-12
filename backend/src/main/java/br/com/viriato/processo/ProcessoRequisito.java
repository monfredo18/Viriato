package br.com.viriato.processo;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** Confirmação manual de um requisito feita em um processo. */
@Entity
@Table(name = "processo_requisito")
@Getter @Setter
public class ProcessoRequisito {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "tenant_id") private Long tenantId;
    @Column(name = "processo_id") private Long processoId;
    @Column(name = "requisito_id") private Long requisitoId;
    @Column(name = "cumprido_por") private Long cumpridoPor;
    @Column(name = "cumprido_em") private Instant cumpridoEm;
}
