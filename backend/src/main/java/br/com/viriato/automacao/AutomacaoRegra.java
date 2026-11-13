package br.com.viriato.automacao;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "automacao_regra")
@Getter @Setter
public class AutomacaoRegra {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "tenant_id") private Long tenantId;
    @Column(name = "fluxo_id") private Long fluxoId;
    @Column(name = "etapa_id") private Long etapaId;
    @Enumerated(EnumType.STRING) private EventoAutomacao evento;
    @Enumerated(EnumType.STRING) private TipoAutomacao tipo;
    private boolean ativo = true;
    private String url;
    private String segredo;
    @Column(name = "criado_em", insertable = false, updatable = false) private Instant criadoEm;
}
