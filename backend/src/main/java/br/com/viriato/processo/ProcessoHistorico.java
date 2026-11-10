package br.com.viriato.processo;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "processo_historico")
@Getter @Setter
public class ProcessoHistorico {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "tenant_id") private Long tenantId;
    @Column(name = "processo_id") private Long processoId;
    @Column(name = "usuario_id") private Long usuarioId;
    private String acao;
    @Column(name = "de_etapa") private String deEtapa;
    @Column(name = "para_etapa") private String paraEtapa;
    private String comentario;
    @Column(name = "criado_em") private Instant criadoEm;

    @PrePersist void antesDeInserir() { criadoEm = Instant.now(); }
}
