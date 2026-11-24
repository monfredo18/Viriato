package br.com.viriato.seguranca;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * A sessão viaja em um cookie {@code HttpOnly} (o JavaScript da página não o lê, então um XSS não rouba o token),
 * {@code SameSite=Strict} e, em HTTPS, {@code Secure}. Como o navegador envia o cookie sozinho, as requisições que
 * alteram dados também precisam do cabeçalho {@code X-XSRF-TOKEN} igual ao cookie {@code XSRF-TOKEN} (duplo envio).
 * O cabeçalho {@code Authorization: Bearer} só vale se {@code viriato.seguranca.aceitar-bearer=true} (integrações e testes).
 */
@Component
public class SessaoCookie {
    public static final String COOKIE_SESSAO = "VIRIATO_SESSAO";
    public static final String COOKIE_CSRF = "XSRF-TOKEN";
    public static final String CABECALHO_CSRF = "X-XSRF-TOKEN";

    /** Quem chegou pelo cookie (e por isso precisa do CSRF) ou pelo cabeçalho Bearer (que o navegador não envia sozinho). */
    public record Credencial(String token, boolean viaCookie) {}

    private static final SecureRandom ALEATORIO = new SecureRandom();

    private final boolean aceitarBearer;
    private final String secure;
    private final Duration vida;

    public SessaoCookie(@Value("${viriato.seguranca.aceitar-bearer:false}") boolean aceitarBearer,
                        @Value("${viriato.seguranca.cookie-secure:auto}") String secure,
                        @Value("${viriato.jwt.expiracao-horas}") long horas) {
        this.aceitarBearer = aceitarBearer;
        this.secure = secure.trim().toLowerCase();
        this.vida = Duration.ofHours(horas);
    }

    public boolean aceitaBearer() { return aceitarBearer; }

    public Optional<Credencial> credencial(HttpServletRequest req) {
        String h = req.getHeader("Authorization");
        if (aceitarBearer && h != null && h.startsWith("Bearer ")) return Optional.of(new Credencial(h.substring(7), false));
        return valor(req, COOKIE_SESSAO).map(t -> new Credencial(t, true));
    }

    public void emitir(HttpServletRequest req, HttpServletResponse res, String token) {
        boolean seguro = ehSeguro(req);
        res.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(COOKIE_SESSAO, token).httpOnly(true).secure(seguro)
                .sameSite("Strict").path("/api").maxAge(vida).build().toString());
        res.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(COOKIE_CSRF, novoCsrf()).httpOnly(false).secure(seguro)
                .sameSite("Strict").path("/").maxAge(vida).build().toString());
    }

    public void limpar(HttpServletRequest req, HttpServletResponse res) {
        boolean seguro = ehSeguro(req);
        res.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(COOKIE_SESSAO, "").httpOnly(true).secure(seguro)
                .sameSite("Strict").path("/api").maxAge(0).build().toString());
        res.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(COOKIE_CSRF, "").httpOnly(false).secure(seguro)
                .sameSite("Strict").path("/").maxAge(0).build().toString());
    }

    /** O cabeçalho precisa ser igual ao cookie (comparação em tempo constante). */
    public boolean csrfConfere(HttpServletRequest req) {
        String cab = req.getHeader(CABECALHO_CSRF);
        Optional<String> cookie = valor(req, COOKIE_CSRF);
        return cab != null && cookie.isPresent() && !cab.isBlank()
                && MessageDigest.isEqual(cab.getBytes(StandardCharsets.UTF_8), cookie.get().getBytes(StandardCharsets.UTF_8));
    }

    public static boolean metodoAltera(String metodo) {
        return !(metodo.equals("GET") || metodo.equals("HEAD") || metodo.equals("OPTIONS") || metodo.equals("TRACE"));
    }

    private boolean ehSeguro(HttpServletRequest req) {
        return switch (secure) {
            case "true" -> true;
            case "false" -> false;
            default -> req.isSecure();   // "auto": com HTTPS (também atrás do proxy, via X-Forwarded-Proto)
        };
    }

    private static Optional<String> valor(HttpServletRequest req, String nome) {
        Cookie[] cs = req.getCookies();
        if (cs == null) return Optional.empty();
        for (Cookie c : cs) if (nome.equals(c.getName()) && c.getValue() != null && !c.getValue().isEmpty()) return Optional.of(c.getValue());
        return Optional.empty();
    }

    private static String novoCsrf() {
        byte[] b = new byte[32];
        ALEATORIO.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
