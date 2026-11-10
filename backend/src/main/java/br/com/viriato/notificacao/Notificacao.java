package br.com.viriato.notificacao;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "notificacao")
@Getter @Setter
public class Notificacao {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "tenant_id") private Long tenantId;
    @Column(name = "usuario_id") private Long usuarioId;
    private String titulo;
    private String mensagem;
    @Column(name = "processo_id") private Long processoId;
    private boolean lida;
    @Column(name = "criado_em", insertable = false, updatable = false)
    private Instant criadoEm;
}
