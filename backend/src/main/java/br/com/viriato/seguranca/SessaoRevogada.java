package br.com.viriato.seguranca;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** Token encerrado antes de expirar (a pessoa saiu do sistema). Some do banco quando o token venceria de qualquer forma. */
@Entity
@Table(name = "sessao_revogada")
@Getter @Setter
public class SessaoRevogada {
    @Id private String jti;
    @Column(name = "expira_em") private Instant expiraEm;
}
