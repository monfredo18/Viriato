package br.com.viriato.cadastro;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** O que pode ser entregue. A entrega define o fluxo (etapas) do processo. */
@Entity
@Table(name = "entrega")
@Getter @Setter
public class Entrega {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "tenant_id") private Long tenantId;
    private String nome;
    @Column(name = "fluxo_id") private Long fluxoId;
    private boolean ativo = true;

    @Override public boolean equals(Object o) { return o instanceof Entrega e && id != null && id.equals(e.id); }
    @Override public int hashCode() { return 31; }
}
