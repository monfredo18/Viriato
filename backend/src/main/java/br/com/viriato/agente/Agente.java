package br.com.viriato.agente;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** O programa instalado no computador de um usuário. O token só existe em hash. */
@Entity
@Table(name = "agente")
@Getter @Setter
public class Agente {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "tenant_id") private Long tenantId;
    @Column(name = "usuario_id") private Long usuarioId;
    private String nome;
    private String versao;
    @Column(name = "token_hash") private String tokenHash;
    private boolean revogado;
    @Column(name = "ultimo_contato") private Instant ultimoContato;
    @Column(name = "criado_em", insertable = false, updatable = false) private Instant criadoEm;
}
