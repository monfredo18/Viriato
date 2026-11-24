package br.com.viriato.cadastro;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** Empresa atendida: para quem o processo é feito. */
@Entity
@Table(name = "cliente")
@Getter @Setter
public class Cliente {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "tenant_id") private Long tenantId;
    private String numero;
    private String nome;
    private boolean ativo = true;

    // ---- ficha fiscal e societária (Fase 2); tudo opcional, só números no CNPJ ----
    private String cnpj;
    @Column(name = "razao_social") private String razaoSocial;
    @Column(name = "nome_fantasia") private String nomeFantasia;
    @Column(name = "inscricao_estadual") private String inscricaoEstadual;
    @Column(name = "inscricao_municipal") private String inscricaoMunicipal;
    @Column(name = "cnae_principal") private String cnaePrincipal;
    @Column(name = "cnae_principal_descricao") private String cnaePrincipalDescricao;
    /** Lista de {codigo, descricao} em JSON. */
    @Column(name = "cnaes_secundarios") private String cnaesSecundarios;
    @Enumerated(EnumType.STRING) @Column(name = "regime_tributario") private br.com.viriato.crm.Enums.RegimeTributario regimeTributario;
    private String uf;
    @Column(name = "codigo_municipio_ibge") private String codigoMunicipioIbge;
    @Column(name = "data_abertura_empresa") private java.time.LocalDate dataAberturaEmpresa;
    @Enumerated(EnumType.STRING) @Column(name = "status_contratual")
    private br.com.viriato.crm.Enums.StatusContratual statusContratual = br.com.viriato.crm.Enums.StatusContratual.ATIVO;
    /** LGPD: quando os dados pessoais foram anonimizados. */
    @Column(name = "anonimizado_em") private java.time.Instant anonimizadoEm;
}
