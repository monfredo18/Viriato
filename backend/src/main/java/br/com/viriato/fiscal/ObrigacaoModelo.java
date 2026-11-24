package br.com.viriato.fiscal;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** Modelo de obrigação fiscal ou contábil (ex.: PGDAS-D): o que fazer, em que entrega, com que periodicidade e vencimento. */
@Entity
@Table(name = "obrigacao_modelo")
@Getter @Setter
public class ObrigacaoModelo {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "tenant_id") private Long tenantId;
    private String nome;
    @Column(name = "entrega_id") private Long entregaId;
    @Enumerated(EnumType.STRING) private Enums.Periodicidade periodicidade;
    @Enumerated(EnumType.STRING) @Column(name = "modo_vencimento") private Enums.ModoVencimento modoVencimento;
    @Column(name = "dia_vencimento") private Integer diaVencimento;
    /** Meses depois do mês da competência em que vence (1 = mês seguinte). */
    @Column(name = "meses_defasagem") private int mesesDefasagem = 1;
    /** JSON: meses da competência em que a obrigação existe, ex.: [1,2,3,…,12] ou [3,6,9,12]. */
    @Column(name = "meses_aplicaveis") private String mesesAplicaveis;
    @Enumerated(EnumType.STRING) @Column(name = "regra_dia_util") private Enums.RegraDiaUtil regraDiaUtil;
    @Enumerated(EnumType.STRING) private Enums.Esfera esfera;
    /** JSON (listas vazias ou ausentes = vale para todos). */
    @Column(name = "regimes_aplicaveis") private String regimesAplicaveis;
    @Column(name = "ufs_aplicaveis") private String ufsAplicaveis;
    @Column(name = "cnaes_aplicaveis") private String cnaesAplicaveis;
    @Column(name = "responsavel_padrao_id") private Long responsavelPadraoId;
    private boolean ativo = true;
    @Column(name = "criado_em", insertable = false, updatable = false) private Instant criadoEm;
}
