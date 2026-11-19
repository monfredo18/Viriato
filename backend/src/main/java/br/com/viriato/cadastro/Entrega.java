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
    /** Setor dono: só ele enxerga e usa esta entrega. Vazio = entrega geral (criada pelo administrador). */
    /** Prazo padrão para concluir (data): vale para os processos novos que não escolherem outra data. */
    @Column(name = "prazo_padrao") private java.time.LocalDate prazoPadrao;
    @Column(name = "setor_dono_id") private Long setorDonoId;

    @Override public boolean equals(Object o) { return o instanceof Entrega e && id != null && id.equals(e.id); }
    @Override public int hashCode() { return 31; }
}
