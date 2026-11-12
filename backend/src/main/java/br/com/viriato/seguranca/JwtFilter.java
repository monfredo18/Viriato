package br.com.viriato.seguranca;

import br.com.viriato.usuario.Usuario;
import br.com.viriato.usuario.UsuarioRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Valida o token e confere o usuário no banco a cada requisição: quem foi desativado ou apagado perde o acesso
 * na hora, o papel vale o atual, e quem ainda está com senha provisória só consegue consultar {@code /api/me}
 * e definir a própria senha.
 */
@Component
public class JwtFilter extends OncePerRequestFilter {
    private final JwtService jwt;
    private final UsuarioRepository usuarios;

    public JwtFilter(JwtService jwt, UsuarioRepository usuarios) {
        this.jwt = jwt;
        this.usuarios = usuarios;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            Optional<Usuario> atual = jwt.validar(header.substring(7))
                    .flatMap(t -> usuarios.findByIdAndTenantId(t.id(), t.tenantId()))
                    .filter(Usuario::isAtivo);
            if (atual.isPresent()) {
                Usuario u = atual.get();
                if (u.isSenhaProvisoria() && !permitidoComSenhaProvisoria(req)) {
                    res.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    res.setContentType("application/json;charset=UTF-8");
                    res.getOutputStream().write("{\"erro\":\"Defina a sua senha antes de continuar\",\"codigo\":\"TROCAR_SENHA\"}"
                            .getBytes(StandardCharsets.UTF_8));
                    return;
                }
                var logado = new UsuarioLogado(u.getId(), u.getTenantId(), u.getPapel());
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                        logado, null, List.of(new SimpleGrantedAuthority("ROLE_" + u.getPapel().name()))));
            }
        }
        chain.doFilter(req, res);
    }

    private static boolean permitidoComSenhaProvisoria(HttpServletRequest req) {
        String caminho = req.getRequestURI();
        return caminho.equals("/api/me") || caminho.equals("/api/me/senha");
    }
}
