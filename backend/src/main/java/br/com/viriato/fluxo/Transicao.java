package br.com.viriato.fluxo;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "transicao")
@Getter @Setter
public class Transicao {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "tenant_id") private Long tenantId;
    @Column(name = "fluxo_id") private Long fluxoId;
    @Column(name = "de_etapa") private Long deEtapa;
    @Column(name = "para_etapa") private Long paraEtapa;
}
