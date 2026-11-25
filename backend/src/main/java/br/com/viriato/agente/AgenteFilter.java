package br.com.viriato.agente;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * As rotas {@code /api/agente/**} só aceitam o token do agente ({@code Authorization: Bearer vagt_...}); o login de pessoa não vale
 * ali, e o token do agente não vale em nenhuma outra rota. Não usa cookie, então não há o que proteger contra CSRF.
 */
@Component
public class AgenteFilter extends OncePerRequestFilter {
    private final AgenteService servico;

    public AgenteFilter(AgenteService servico) { this.servico = servico; }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) { return !req.getRequestURI().startsWith("/api/agente/"); }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        SecurityContextHolder.clearContext();
        String h = req.getHeader("Authorization");
        Optional<Agente> a = h != null && h.startsWith("Bearer ") ? servico.autenticar(h.substring(7).trim()) : Optional.empty();
        a.ifPresent(ag -> SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new AgenteLogado(ag.getId()), null, List.of(new SimpleGrantedAuthority("ROLE_AGENTE")))));
        chain.doFilter(req, res);
    }
}
