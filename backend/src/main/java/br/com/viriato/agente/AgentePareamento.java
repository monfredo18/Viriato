package br.com.viriato.agente;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "agente_pareamento")
@Getter @Setter
public class AgentePareamento {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "tenant_id") private Long tenantId;
    @Column(name = "usuario_id") private Long usuarioId;
    @Column(name = "codigo_hash") private String codigoHash;
    @Column(name = "expira_em") private Instant expiraEm;
    private boolean usado;
}
