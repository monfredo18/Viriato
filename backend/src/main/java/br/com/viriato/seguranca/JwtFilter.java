package br.com.viriato.seguranca;

import br.com.viriato.usuario.Papel;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Valida o token e confere o usuário no banco a cada requisição: quem foi desativado ou apagado perde o acesso
 * na hora, quem teve a senha zerada (ou saiu do sistema) perde as sessões antigas, o papel vale o atual, e quem ainda está com senha provisória só consegue consultar {@code /api/me}
 * e definir a própria senha.
 */
@Component
public class JwtFilter extends OncePerRequestFilter {
    private final JwtService jwt;
    private final UsuarioRepository usuarios;
    private final SessaoRevogadaRepository revogadas;
    private final SessaoCookie cookies;
    private final boolean exigirDoisFatoresAdmin;

    public JwtFilter(JwtService jwt, UsuarioRepository usuarios, SessaoRevogadaRepository revogadas, SessaoCookie cookies,
                     @Value("${viriato.seguranca.exigir-2fa-admin:false}") boolean exigirDoisFatoresAdmin) {
        this.jwt = jwt;
        this.usuarios = usuarios;
        this.revogadas = revogadas;
        this.cookies = cookies;
        this.exigirDoisFatoresAdmin = exigirDoisFatoresAdmin;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        Optional<SessaoCookie.Credencial> cred = req.getRequestURI().startsWith("/api/auth/") ? Optional.empty() : cookies.credencial(req);
        if (cred.isPresent() && cred.get().viaCookie() && SessaoCookie.metodoAltera(req.getMethod()) && !cookies.csrfConfere(req)) {
            recusar(res, "Requisição recusada: falta o código anti-CSRF", "CSRF");   // o cookie vai sozinho; o cabeçalho prova que a chamada veio da nossa tela
            return;
        }
        if (cred.isPresent()) {
            Optional<Usuario> atual = jwt.validar(cred.get().token())
                    .filter(t -> t.jti() == null || !revogadas.existsById(t.jti()))   // quem saiu não volta com o mesmo token
                    .flatMap(t -> usuarios.findByIdAndTenantId(t.usuario().id(), t.usuario().tenantId())
                            .filter(u -> u.getVersaoSessao() == t.versaoSessao()))
                    .filter(Usuario::isAtivo);
            if (atual.isPresent()) {
                Usuario u = atual.get();
                if (u.isSenhaProvisoria() && !permitidoComSenhaProvisoria(req)) {
                    recusar(res, "Defina a sua senha antes de continuar", "TROCAR_SENHA");
                    return;
                }
                if (exigirDoisFatoresAdmin && u.getPapel() == Papel.ADMIN && !u.isTotpAtivo() && !permitidoSemDoisFatores(req)) {
                    recusar(res, "Ative a verificação em duas etapas para continuar", "ATIVAR_2FA");
                    return;
                }
                var logado = new UsuarioLogado(u.getId(), u.getTenantId(), u.getPapel());
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                        logado, null, List.of(new SimpleGrantedAuthority("ROLE_" + u.getPapel().name()))));
            }
        }
        chain.doFilter(req, res);
    }

    private static void recusar(HttpServletResponse res, String erro, String codigo) throws IOException {
        res.setStatus(HttpServletResponse.SC_FORBIDDEN);
        res.setContentType("application/json;charset=UTF-8");
        res.getOutputStream().write(("{\"erro\":\"" + erro + "\",\"codigo\":\"" + codigo + "\"}").getBytes(StandardCharsets.UTF_8));
    }

    /** Administrador sem duas etapas (quando exigidas) só consulta a si mesmo, configura a verificação e sai. */
    private static boolean permitidoSemDoisFatores(HttpServletRequest req) {
        String c = req.getRequestURI();
        return c.equals("/api/me") || c.equals("/api/me/sair") || c.startsWith("/api/me/2fa/");
    }

    private static boolean permitidoComSenhaProvisoria(HttpServletRequest req) {
        String caminho = req.getRequestURI();
        return caminho.equals("/api/me") || caminho.equals("/api/me/senha");
    }
}
