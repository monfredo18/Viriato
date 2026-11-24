package br.com.viriato.ged;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** Onde parou a consulta à SEFAZ de cada empresa atendida (último NSU) e quando se pode consultar de novo. */
@Entity
@Table(name = "dfe_controle")
@Getter @Setter
public class DfeControle {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "tenant_id") private Long tenantId;
    @Column(name = "cliente_id") private Long clienteId;
    @Column(name = "ultimo_nsu") private String ultimoNsu = "0";
    @Column(name = "ultima_consulta") private Instant ultimaConsulta;
    @Column(name = "proxima_consulta") private Instant proximaConsulta;
    private String status;
    private String mensagem;
}
