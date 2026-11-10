package br.com.viriato.fluxo;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "etapa")
@Getter @Setter
public class Etapa {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "tenant_id") private Long tenantId;
    @Column(name = "fluxo_id") private Long fluxoId;
    private String nome;
    private int ordem;
    @Column(name = "prazo_dias") private Integer prazoDias;
    private boolean inicial;
    @Column(name = "final_") private boolean finalEtapa;
}
