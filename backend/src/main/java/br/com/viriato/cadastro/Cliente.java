package br.com.viriato.cadastro;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** Empresa atendida: para quem o processo é feito. */
@Entity
@Table(name = "cliente")
@Getter @Setter
public class Cliente {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "tenant_id") private Long tenantId;
    private String numero;
    private String nome;
    /** Opcional: para a automação que avisa a empresa atendida por e-mail. */
    private String email;
    private boolean ativo = true;
}
