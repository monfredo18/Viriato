package br.com.viriato.auditoria;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** Registro de um fato de segurança ou de administração (quem fez o quê, e quando). */
@Entity
@Table(name = "auditoria")
@Getter @Setter
public class Auditoria {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "tenant_id") private Long tenantId;
    @Column(name = "usuario_id") private Long usuarioId;
    /** Nome guardado na hora: continua certo se a pessoa for apagada. */
    @Column(name = "usuario_nome") private String usuarioNome;
    private String acao;
    private String detalhe;
    @Column(name = "criado_em") private Instant criadoEm = Instant.now();
}
