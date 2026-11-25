package br.com.viriato.crm;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** Dados públicos de um certificado digital instalado em um computador com o agente. A chave privada nunca chega aqui. */
@Entity
@Table(name = "cliente_certificado")
@Getter @Setter
public class ClienteCertificado {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "tenant_id") private Long tenantId;
    /** Empresa atendida a que o certificado pertence (vazio enquanto não for reconhecida ou escolhida). */
    @Column(name = "cliente_id") private Long clienteId;
    /** SHA-1 do certificado, em maiúsculas: é como o Windows e o agente o identificam. */
    private String thumbprint;
    private String titular;
    @Column(name = "cnpj_certificado") private String cnpjCertificado;
    private String emissora;
    @Column(name = "numero_serie") private String numeroSerie;
    @Column(name = "valido_de") private java.time.LocalDate validoDe;
    @Column(name = "valido_ate") private java.time.LocalDate validoAte;
    /** A empresa foi escolhida por uma pessoa: o agente não troca mais. */
    @Column(name = "vinculo_manual") private boolean vinculoManual;
    /** Está instalado em pelo menos um computador com agente. */
    private boolean ativo = true;
    @Column(name = "visto_em") private java.time.Instant vistoEm;
    @Column(name = "criado_em", insertable = false, updatable = false) private java.time.Instant criadoEm;
}
