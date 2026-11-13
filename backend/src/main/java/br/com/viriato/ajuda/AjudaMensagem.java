package br.com.viriato.ajuda;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "ajuda_mensagem")
@Getter @Setter
public class AjudaMensagem {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "tenant_id") private Long tenantId;
    @Column(name = "conversa_id") private Long conversaId;
    @Column(name = "autor_id") private Long autorId;
    /** Nome de quem escreveu, guardado na hora: continua certo se a pessoa for removida. */
    @Column(name = "autor_nome") private String autorNome;
    @Column(name = "de_admin") private boolean deAdmin;
    private String texto;
    @Column(name = "criado_em") private Instant criadoEm = Instant.now();
}
