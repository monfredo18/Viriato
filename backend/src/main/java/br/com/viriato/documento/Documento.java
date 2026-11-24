package br.com.viriato.documento;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "documento")
@Getter @Setter
public class Documento {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "tenant_id") private Long tenantId;
    @Column(name = "processo_id") private Long processoId;
    @Column(name = "nome_original") private String nomeOriginal;
    @Column(name = "chave_s3") private String chaveS3;
    @Column(name = "content_type") private String contentType;
    private long tamanho;
    @Column(name = "enviado_por") private Long enviadoPor;
    /** Gerado pelo sistema (automação), sem autor. */
    private boolean automatico;
    /** Requisito de etapa que este documento cumpre (opcional). */
    @Column(name = "requisito_id") private Long requisitoId;
    /** GED: empresa, setor e período da pasta (ano, mês), categoria, guarda legal e arquivamento. */
    @Column(name = "cliente_id") private Long clienteId;
    @Column(name = "setor_id") private Long setorId;
    private Integer ano;
    private Integer mes;
    private String categoria;
    /** Guarda legal (mínimo de 5 anos): não se exclui, só se arquiva, com trilha na auditoria. */
    @Column(name = "guarda_legal") private boolean guardaLegal;
    private boolean arquivado;
    @Column(name = "arquivado_em") private Instant arquivadoEm;
    @Column(name = "arquivado_por") private Long arquivadoPor;
    @Column(name = "criado_em") private Instant criadoEm;

    @PrePersist void antesDeInserir() { criadoEm = Instant.now(); }
}
