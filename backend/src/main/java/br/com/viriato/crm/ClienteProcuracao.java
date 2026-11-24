package br.com.viriato.crm;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "cliente_procuracao")
@Getter @Setter
public class ClienteProcuracao {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "tenant_id") private Long tenantId;
    @Column(name = "cliente_id") private Long clienteId;
    @Enumerated(EnumType.STRING) private Enums.TipoProcuracao tipo;
    @Column(name = "data_outorga") private java.time.LocalDate dataOutorga;
    @Column(name = "data_vencimento") private java.time.LocalDate dataVencimento;
    private String poderes;
    @Column(name = "comprovante_chave") private String comprovanteChave;
    @Column(name = "comprovante_nome") private String comprovanteNome;
    private boolean ativo = true;
    @Column(name = "criado_em", insertable = false, updatable = false) private java.time.Instant criadoEm;
}
