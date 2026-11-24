package br.com.viriato.crm;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "cliente_contato")
@Getter @Setter
public class ClienteContato {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "tenant_id") private Long tenantId;
    @Column(name = "cliente_id") private Long clienteId;
    private String nome;
    @Enumerated(EnumType.STRING) private Enums.AreaContato area;
    private String email;
    private String telefone;
    @Column(name = "recebe_guias") private boolean recebeGuias;
    @Column(name = "recebe_cobrancas") private boolean recebeCobrancas;
    @Column(name = "criado_em", insertable = false, updatable = false) private java.time.Instant criadoEm;
}
