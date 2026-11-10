package br.com.viriato.fluxo;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "fluxo")
@Getter @Setter
public class Fluxo {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "tenant_id") private Long tenantId;
    private String nome;
    private boolean ativo = true;
}
