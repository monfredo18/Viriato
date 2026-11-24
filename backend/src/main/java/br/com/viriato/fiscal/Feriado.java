package br.com.viriato.fiscal;

import jakarta.persistence.*;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

/** Feriado da empresa (cada empresa do sistema tem o seu calendário). Fixo anual (dia e mês) ou numa data exata. */
@Entity
@Table(name = "feriado")
@Getter @Setter
public class Feriado {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "tenant_id") private Long tenantId;
    @Enumerated(EnumType.STRING) private Enums.EscopoFeriado escopo;
    private String uf;
    @Column(name = "codigo_municipio_ibge") private String codigoMunicipioIbge;
    private LocalDate data;
    private Integer dia;
    private Integer mes;
    private String descricao;
    private boolean ativo = true;
}
