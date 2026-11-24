package br.com.viriato.fiscal;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** Obrigação vinculada a uma empresa atendida (sugerida pelo regime, UF e CNAE, ou escolhida à mão). */
@Entity
@Table(name = "cliente_obrigacao")
@Getter @Setter
public class ClienteObrigacao {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "tenant_id") private Long tenantId;
    @Column(name = "cliente_id") private Long clienteId;
    @Column(name = "modelo_id") private Long modeloId;
    private boolean ativo = true;
    @Column(name = "responsavel_id") private Long responsavelId;
    private boolean personalizado;
}
