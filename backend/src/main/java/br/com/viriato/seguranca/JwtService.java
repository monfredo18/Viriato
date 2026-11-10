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
    private final Duration expiracao;

    public JwtService(@Value("${viriato.jwt.secret}") String segredo,
                      @Value("${viriato.jwt.expiracao-horas}") long horas) {
        if (segredo.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("viriato.jwt.secret precisa ter ao menos 32 bytes");
        }
        this.chave = Keys.hmacShaKeyFor(segredo.getBytes(StandardCharsets.UTF_8));
        this.expiracao = Duration.ofHours(horas);
    }

    public String gerar(Usuario u) {
        Instant agora = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(u.getId()))
                .claim("tid", u.getTenantId())
                .claim("papel", u.getPapel().name())
                .issuedAt(Date.from(agora))
                .expiration(Date.from(agora.plus(expiracao)))
                .signWith(chave)
                .compact();
    }

    public Optional<UsuarioLogado> validar(String token) {
        try {
            Claims c = Jwts.parser().verifyWith(chave).build().parseSignedClaims(token).getPayload();
            return Optional.of(new UsuarioLogado(
                    Long.valueOf(c.getSubject()),
                    c.get("tid", Long.class),
                    Papel.valueOf(c.get("papel", String.class))));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
