package br.com.viriato.cadastro;

import jakarta.persistence.*;
import java.util.HashSet;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;

/** Departamento. Cada setor define quais entregas pode fazer. */
@Entity
@Table(name = "setor")
@Getter @Setter
public class Setor {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "tenant_id") private Long tenantId;
    private String nome;
    private boolean ativo = true;

    @ManyToMany
    @JoinTable(name = "setor_entrega",
            joinColumns = @JoinColumn(name = "setor_id"),
            inverseJoinColumns = @JoinColumn(name = "entrega_id"))
    private Set<Entrega> entregas = new HashSet<>();
}
