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
    /** Requisito de etapa que este documento cumpre (opcional). */
    @Column(name = "requisito_id") private Long requisitoId;
    @Column(name = "criado_em") private Instant criadoEm;

    @PrePersist void antesDeInserir() { criadoEm = Instant.now(); }
}
