package br.com.viriato.ajuda;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** Conversa de uma pessoa com os administradores da empresa (uma por pessoa). */
@Entity
@Table(name = "ajuda_conversa")
@Getter @Setter
public class AjudaConversa {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "tenant_id") private Long tenantId;
    @Column(name = "usuario_id") private Long usuarioId;
    @Column(name = "nao_lidas_admin") private int naoLidasAdmin;
    @Column(name = "nao_lidas_usuario") private int naoLidasUsuario;
    @Column(name = "ultima_texto") private String ultimaTexto;
    @Column(name = "ultima_de_admin") private boolean ultimaDeAdmin;
    @Column(name = "atualizado_em") private Instant atualizadoEm = Instant.now();
}
