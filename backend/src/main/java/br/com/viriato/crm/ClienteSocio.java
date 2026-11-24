package br.com.viriato.crm;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "cliente_socio")
@Getter @Setter
public class ClienteSocio {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "tenant_id") private Long tenantId;
    @Column(name = "cliente_id") private Long clienteId;
    private String nome;
    @Column(name = "cpf_cifrado") private String cpfCifrado;
    @Column(name = "cpf_mascara") private String cpfMascara;
    private java.math.BigDecimal percentual;
    @Column(name = "responsavel_legal") private boolean responsavelLegal;
    private String email;
    private String telefone;
    @Column(name = "criado_em", insertable = false, updatable = false) private java.time.Instant criadoEm;
}
