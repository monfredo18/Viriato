package br.com.viriato.fiscal;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** "O modelo depende do predecessor": na mesma competência e empresa, o processo do modelo só avança quando o do predecessor conclui. */
@Entity
@Table(name = "obrigacao_modelo_dependencia")
@Getter @Setter
public class ObrigacaoModeloDependencia {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "tenant_id") private Long tenantId;
    @Column(name = "modelo_id") private Long modeloId;
    @Column(name = "predecessor_modelo_id") private Long predecessorModeloId;
}
