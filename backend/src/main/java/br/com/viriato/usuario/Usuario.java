package br.com.viriato.usuario;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "usuario")
@Getter @Setter
public class Usuario {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "tenant_id") private Long tenantId;
    private String nome;
    private String email;
    @Column(name = "senha_hash") private String senhaHash;
    @Enumerated(EnumType.STRING) private Papel papel;
    private boolean ativo = true;
    @Column(name = "criado_em", insertable = false, updatable = false)
    private Instant criadoEm;
}
