package br.com.viriato.crm;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "alerta_vencimento")
@Getter @Setter
public class AlertaVencimento {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "tenant_id") private Long tenantId;
    private String tipo;
    @Column(name = "ref_id") private Long refId;
    private int marco;
}
