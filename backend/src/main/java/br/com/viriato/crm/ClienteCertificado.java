package br.com.viriato.crm;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "cliente_certificado")
@Getter @Setter
public class ClienteCertificado {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "tenant_id") private Long tenantId;
    @Column(name = "cliente_id") private Long clienteId;
    @Enumerated(EnumType.STRING) private Enums.TipoCertificado tipo;
    private String titular;
    @Column(name = "cnpj_certificado") private String cnpjCertificado;
    private String emissora;
    @Column(name = "numero_serie") private String numeroSerie;
    @Column(name = "valido_de") private java.time.LocalDate validoDe;
    @Column(name = "valido_ate") private java.time.LocalDate validoAte;
    /** A1: arquivo .pfx cifrado (AES-256-GCM) no armazenamento. Nunca sai pela API. */
    @Column(name = "arquivo_chave") private String arquivoChave;
    /** A1: senha do .pfx cifrada (AES-256-GCM). Nunca sai pela API. */
    @Column(name = "senha_cifrada") private String senhaCifrada;
    private String observacao;
    private boolean ativo = true;
    @Column(name = "criado_por") private Long criadoPor;
    @Column(name = "criado_em", insertable = false, updatable = false) private java.time.Instant criadoEm;
}
