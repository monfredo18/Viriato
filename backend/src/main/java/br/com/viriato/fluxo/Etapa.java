package br.com.viriato.fluxo;

import br.com.viriato.usuario.Papel;
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
    /** Menor papel que pode mover um processo PARA esta etapa; os demais precisam pedir aprovação. */
    @Enumerated(EnumType.STRING) @Column(name = "papel_minimo") private Papel papelMinimo = Papel.USUARIO;
}
