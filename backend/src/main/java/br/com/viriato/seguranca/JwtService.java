package br.com.viriato.seguranca;

import br.com.viriato.usuario.Papel;
import br.com.viriato.usuario.Usuario;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class JwtService {
    private final SecretKey chave;
    private final io.jsonwebtoken.JwtParser parser;
    private final Duration expiracao;

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(JwtService.class);
    /** Valores de exemplo que acompanham o código e o docker-compose: nunca devem proteger um sistema de verdade. */
    private static final java.util.List<String> SEGREDOS_DE_EXEMPLO = java.util.List.of("dev-only-secret", "troque-este-segredo");

    public JwtService(@Value("${viriato.jwt.secret}") String segredo,
                      @Value("${viriato.jwt.expiracao-horas}") long horas,
                      @Value("${viriato.jwt.permitir-padrao:false}") boolean permitirPadrao) {
        if (SEGREDOS_DE_EXEMPLO.stream().anyMatch(segredo::startsWith)) {
            if (!permitirPadrao) {
                throw new IllegalStateException("JWT_SECRET está com o valor de exemplo. Defina um segredo próprio (openssl rand -base64 48). "
                        + "Só em desenvolvimento local, defina VIRIATO_JWT_PERMITIR_PADRAO=true para aceitar o exemplo.");
            }
            log.warn("ATENÇÃO: usando o JWT_SECRET de exemplo. Não use este segredo em produção.");
        }
        if (segredo.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("viriato.jwt.secret precisa ter ao menos 32 bytes");
        }
        this.chave = Keys.hmacShaKeyFor(segredo.getBytes(StandardCharsets.UTF_8));
        this.parser = Jwts.parser().verifyWith(chave).build();
        this.expiracao = Duration.ofHours(horas);
    }

    public String gerar(Usuario u) {
        Instant agora = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(u.getId()))
                .claim("tid", u.getTenantId())
                .claim("papel", u.getPapel().name())
                .claim("v", u.getVersaoSessao())
                .id(java.util.UUID.randomUUID().toString())
                .issuedAt(Date.from(agora))
                .expiration(Date.from(agora.plus(expiracao)))
                .signWith(chave)
                .compact();
    }

    /** Identidade do token e a versão de sessão em que foi emitido (tokens antigos, sem versão, contam como 0). */
    public record TokenValido(UsuarioLogado usuario, int versaoSessao, String jti, Instant expira) {}

    public Optional<TokenValido> validar(String token) {
        try {
            Claims c = parser.parseSignedClaims(token).getPayload();
            Integer versao = c.get("v", Integer.class);
            return Optional.of(new TokenValido(new UsuarioLogado(
                    Long.valueOf(c.getSubject()),
                    c.get("tid", Long.class),
                    Papel.valueOf(c.get("papel", String.class))), versao == null ? 0 : versao,
                    c.getId(), c.getExpiration() == null ? null : c.getExpiration().toInstant()));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
