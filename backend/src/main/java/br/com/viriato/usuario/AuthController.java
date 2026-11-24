package br.com.viriato.usuario;

import br.com.viriato.comum.NaoEncontradoException;
import br.com.viriato.seguranca.Contexto;
import br.com.viriato.seguranca.IpCliente;
import br.com.viriato.seguranca.SessaoCookie;
import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class AuthController {
    private final AuthService auth;
    private final UsuarioRepository usuarios;
    private final boolean cadastroAberto;
    private final boolean exigirDoisFatoresAdmin;
    private final SessaoCookie cookies;
    private final IpCliente ips;

    public AuthController(AuthService auth, UsuarioRepository usuarios, SessaoCookie cookies, IpCliente ips,
                          @Value("${viriato.cadastro-empresa-aberto:false}") boolean cadastroAberto,
                          @Value("${viriato.seguranca.exigir-2fa-admin:false}") boolean exigirDoisFatoresAdmin) {
        this.auth = auth;
        this.usuarios = usuarios;
        this.cookies = cookies;
        this.ips = ips;
        this.cadastroAberto = cadastroAberto;
        this.exigirDoisFatoresAdmin = exigirDoisFatoresAdmin;
    }

    /** O token vai no cookie HttpOnly; só aparece no corpo quando o Bearer está habilitado (integrações e testes). */
    private AuthService.Sessao entregar(AuthService.Sessao s, HttpServletRequest req, HttpServletResponse res) {
        cookies.emitir(req, res, s.token());
        return cookies.aceitaBearer() ? s : s.semToken();
    }

    /** Entra-se com nome e senha. Os apelidos "nome" e "email" existem para clientes e contas antigas. */
    public record LoginReq(@JsonAlias({"nome", "email"}) @NotBlank String usuario, @NotBlank String senha, String codigo) {}

    public record AlterarSenhaReq(@NotBlank String senhaAtual, @NotBlank @Size(min = 8, max = 72) String novaSenha) {}
    public record CodigoReq(@NotBlank String codigo) {}
    public record SenhaReq(@NotBlank String senha) {}

    public record RegistroReq(@NotBlank @Size(max = 150) String empresa,
                              @NotBlank @Size(max = 150) String nome,
                              @NotBlank @Email String email,
                              @NotBlank @Size(min = 8, max = 72) String senha) {}

    public record NovaSenhaReq(@NotBlank @Size(min = 8, max = 72) String novaSenha) {}

    /** Aberta a todos e sem consultar o banco: a tela a consulta de tempos em tempos para mostrar "Sistema online". */
    @GetMapping("/auth/saude")
    public java.util.Map<String, String> saude() { return java.util.Map.of("status", "ok"); }

    @PostMapping("/auth/login")
    public AuthService.Sessao login(@Valid @RequestBody LoginReq r, HttpServletRequest req, HttpServletResponse res) {
        return entregar(auth.login(r.usuario(), r.senha(), r.codigo(), ips.de(req), req.getHeader("User-Agent")), req, res);
    }

    /** Desligado por padrão: a primeira conta de administrador nasce da configuração (ver README). */
    @PostMapping("/auth/registrar-empresa")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthService.Sessao registrar(@Valid @RequestBody RegistroReq r, HttpServletRequest req, HttpServletResponse res) {
        if (!cadastroAberto) throw new NaoEncontradoException("Cadastro de empresas");
        return entregar(auth.registrarEmpresa(r.empresa(), r.nome(), r.email(), r.senha()), req, res);
    }

    @GetMapping("/me")
    public UsuarioDto me() {
        return usuarios.findByIdAndTenantId(Contexto.atual().id(), Contexto.tenantId())
                .map(u -> UsuarioDto.de(u).comExigenciaDeDoisFatores(exigirDoisFatoresAdmin)).orElseThrow();
    }

    /** Primeiro acesso: troca a senha inicial (ou zerada) pela senha própria. */
    @PutMapping("/me/senha")
    public UsuarioDto definirSenha(@Valid @RequestBody NovaSenhaReq r) {
        return auth.definirSenhaPropria(Contexto.atual(), r.novaSenha()).comExigenciaDeDoisFatores(exigirDoisFatoresAdmin);
    }

    /** Sair: o token usado deixa de valer na hora. */
    @PostMapping("/me/sair")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void sair(HttpServletRequest req, HttpServletResponse res) {
        cookies.credencial(req).ifPresent(c -> auth.sair(c.token()));
        cookies.limpar(req, res);
    }

    /** Troca da própria senha, informando a atual; devolve uma sessão nova (as outras são encerradas). */
    @PostMapping("/me/alterar-senha")
    public AuthService.Sessao alterarSenha(@Valid @RequestBody AlterarSenhaReq r, HttpServletRequest req, HttpServletResponse res) {
        return entregar(auth.alterarSenha(Contexto.atual(), r.senhaAtual(), r.novaSenha()), req, res);
    }

    @PostMapping("/me/2fa/iniciar")
    public AuthService.ConfiguracaoDoisFatores iniciarDoisFatores() { return auth.iniciarDoisFatores(Contexto.atual()); }

    @PostMapping("/me/2fa/ativar")
    public UsuarioDto ativarDoisFatores(@Valid @RequestBody CodigoReq r) { return auth.ativarDoisFatores(Contexto.atual(), r.codigo()).comExigenciaDeDoisFatores(exigirDoisFatoresAdmin); }

    @PostMapping("/me/2fa/desativar")
    public UsuarioDto desativarDoisFatores(@Valid @RequestBody SenhaReq r) { return auth.desativarDoisFatores(Contexto.atual(), r.senha()).comExigenciaDeDoisFatores(exigirDoisFatoresAdmin); }
}
