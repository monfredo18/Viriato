package br.com.viriato.fluxo;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** Algo que precisa estar cumprido para o processo SAIR da etapa (avançar). */
@Entity
@Table(name = "etapa_requisito")
@Getter @Setter
public class EtapaRequisito {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "tenant_id") private Long tenantId;
    @Column(name = "etapa_id") private Long etapaId;
    @Enumerated(EnumType.STRING) private TipoRequisito tipo;
    private String descricao;
    private int ordem;
}
