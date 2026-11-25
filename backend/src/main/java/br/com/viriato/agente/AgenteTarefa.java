package br.com.viriato.agente;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "agente_tarefa")
@Getter @Setter
public class AgenteTarefa {
    public static final String PENDENTE = "PENDENTE", EM_EXECUCAO = "EM_EXECUCAO", CONCLUIDA = "CONCLUIDA", ERRO = "ERRO", EXPIRADA = "EXPIRADA";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "tenant_id") private Long tenantId;
    @Column(name = "certificado_id") private Long certificadoId;
    @Column(name = "agente_id") private Long agenteId;
    private String tipo;
    private String url;
    @Column(name = "content_type") private String contentType;
    /** Corpo da chamada, em base64. Apagado quando a tarefa termina. */
    @Column(columnDefinition = "MEDIUMTEXT") private String corpo;
    private String status;
    /** Corpo da resposta, em base64. Apagado assim que o sistema o lê. */
    @Column(columnDefinition = "MEDIUMTEXT") private String resposta;
    @Column(name = "http_status") private Integer httpStatus;
    private String erro;
    @Column(name = "criado_em", insertable = false, updatable = false) private Instant criadoEm;
    @Column(name = "atendido_em") private Instant atendidoEm;
}
