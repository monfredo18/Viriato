package br.com.viriato.processo;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** "processoId" só avança quando "predecessorId" estiver concluído. */
@Entity
@Table(name = "processo_dependencia")
@Getter @Setter
public class ProcessoDependencia {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "tenant_id") private Long tenantId;
    @Column(name = "processo_id") private Long processoId;
    @Column(name = "predecessor_id") private Long predecessorId;
}
