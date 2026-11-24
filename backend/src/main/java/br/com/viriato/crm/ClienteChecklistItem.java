package br.com.viriato.crm;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "cliente_checklist_item")
@Getter @Setter
public class ClienteChecklistItem {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "tenant_id") private Long tenantId;
    @Column(name = "cliente_id") private Long clienteId;
    @Enumerated(EnumType.STRING) private Enums.TipoChecklist tipo;
    private int ordem;
    private String descricao;
    private boolean concluido;
    @Column(name = "concluido_por") private Long concluidoPor;
    @Column(name = "concluido_em") private java.time.Instant concluidoEm;
}
