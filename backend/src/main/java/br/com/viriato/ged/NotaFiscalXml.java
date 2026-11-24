package br.com.viriato.ged;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

/** Nota fiscal eletrônica lida de um XML (NF-e, NFC-e, CT-e ou NFS-e), ligada à empresa atendida. */
@Entity
@Table(name = "documento_fiscal_xml")
@Getter @Setter
public class NotaFiscalXml {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "tenant_id") private Long tenantId;
    @Column(name = "cliente_id") private Long clienteId;
    @Column(name = "documento_id") private Long documentoId;
    /** 44 dígitos (NF-e, NFC-e, CT-e); na NFS-e, o identificador do leiaute ou "NFSE-cnpj-número-verificação". */
    @Column(name = "chave_acesso") private String chaveAcesso;
    private String modelo;
    @Column(name = "numero_nota") private String numeroNota;
    private String serie;
    @Column(name = "data_emissao") private LocalDate dataEmissao;
    @Column(name = "competencia_ano") private Integer competenciaAno;
    @Column(name = "competencia_mes") private Integer competenciaMes;
    @Column(name = "cnpj_emitente") private String cnpjEmitente;
    @Column(name = "nome_emitente") private String nomeEmitente;
    @Column(name = "cnpj_destinatario") private String cnpjDestinatario;
    @Column(name = "nome_destinatario") private String nomeDestinatario;
    @Column(name = "tipo_operacao") private String tipoOperacao;
    @Column(name = "valor_total") private BigDecimal valorTotal = BigDecimal.ZERO;
    @Column(name = "valor_icms") private BigDecimal valorIcms = BigDecimal.ZERO;
    @Column(name = "valor_ipi") private BigDecimal valorIpi = BigDecimal.ZERO;
    @Column(name = "valor_pis") private BigDecimal valorPis = BigDecimal.ZERO;
    @Column(name = "valor_cofins") private BigDecimal valorCofins = BigDecimal.ZERO;
    @Column(name = "valor_iss") private BigDecimal valorIss = BigDecimal.ZERO;
    private String cfops;
    private String ncms;
    private String origem;
    @Column(name = "criado_em", insertable = false, updatable = false) private Instant criadoEm;
}
