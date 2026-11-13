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
    /** Senha inicial ou zerada pelo administrador: a pessoa precisa criar a própria antes de usar o sistema. */
    @Column(name = "senha_provisoria") private boolean senhaProvisoria;
    /** Sobe quando a senha é zerada: tokens emitidos antes deixam de valer. */
    @Column(name = "versao_sessao") private int versaoSessao;
    @Enumerated(EnumType.STRING) private Papel papel;
    private boolean ativo = true;
    @Column(name = "setor_id") private Long setorId;
    /** Foto de perfil (opcional): chave no armazenamento, tipo e versão (instante do envio, em milissegundos). */
    @Column(name = "foto_chave") private String fotoChave;
    @Column(name = "foto_tipo") private String fotoTipo;
    @Column(name = "foto_versao") private Long fotoVersao;
    @Column(name = "criado_em", insertable = false, updatable = false)
    private Instant criadoEm;
}
