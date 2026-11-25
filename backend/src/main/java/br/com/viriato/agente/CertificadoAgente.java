package br.com.viriato.agente;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** Liga um certificado ao computador (agente) onde ele está instalado. */
@Entity
@Table(name = "certificado_agente")
@Getter @Setter
public class CertificadoAgente {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "certificado_id") private Long certificadoId;
    @Column(name = "agente_id") private Long agenteId;
    @Column(name = "visto_em") private Instant vistoEm;
}
