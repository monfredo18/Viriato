package br.com.viriato.tenant;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "tenant")
@Getter @Setter
public class Tenant {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String nome;
    private boolean ativo = true;
    @Column(name = "criado_em", insertable = false, updatable = false)
    private Instant criadoEm;
}
