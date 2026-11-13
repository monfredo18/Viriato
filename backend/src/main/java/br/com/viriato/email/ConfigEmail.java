package br.com.viriato.email;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** Servidor SMTP da empresa. */
@Entity
@Table(name = "tenant_email")
@Getter @Setter
public class ConfigEmail {
    @Id @Column(name = "tenant_id") private Long tenantId;
    private String host;
    private int porta;
    @Enumerated(EnumType.STRING) private SegurancaEmail seguranca;
    private String usuario;
    @Column(name = "senha_cifrada") private String senhaCifrada;
    private String remetente;
    @Column(name = "atualizado_em") private Instant atualizadoEm;

    @PrePersist @PreUpdate void marcar() { atualizadoEm = Instant.now(); }
}
