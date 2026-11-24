package br.com.viriato.processo;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** Campo extra definido para uma entrega: aparece, tipado, no detalhe dos processos dela. */
@Entity
@Table(name = "entrega_campo")
@Getter @Setter
public class EntregaCampo {
    public enum Tipo { TEXTO, NUMERO, DATA, MOEDA, SELECAO }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "tenant_id") private Long tenantId;
    @Column(name = "entrega_id") private Long entregaId;
    private String chave;
    private String rotulo;
    @Enumerated(EnumType.STRING) private Tipo tipo;
    private boolean obrigatorio;
    /** JSON: lista de opções (campo de seleção). */
    private String opcoes;
    private int ordem;
}
