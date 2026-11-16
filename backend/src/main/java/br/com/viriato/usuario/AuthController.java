package br.com.viriato.usuario;

import br.com.viriato.comum.NaoEncontradoException;
import br.com.viriato.seguranca.Contexto;
import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.servlet.http.HttpServletRequest;
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

    public AuthController(AuthService auth, UsuarioRepository usuarios,
                          @Value("${viriato.cadastro-empresa-aberto:false}") boolean cadastroAberto) {
        this.auth = auth;
        this.usuarios = usuarios;
        this.cadastroAberto = cadastroAberto;
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
    public AuthService.Sessao login(@Valid @RequestBody LoginReq r) {
        return auth.login(r.usuario(), r.senha(), r.codigo());
    }

    /** Desligado por padrão: a primeira conta de administrador nasce da configuração (ver README). */
    @PostMapping("/auth/registrar-empresa")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthService.Sessao registrar(@Valid @RequestBody RegistroReq r) {
        if (!cadastroAberto) throw new NaoEncontradoException("Cadastro de empresas");
        return auth.registrarEmpresa(r.empresa(), r.nome(), r.email(), r.senha());
    }

    @GetMapping("/me")
    public UsuarioDto me() {
        return usuarios.findByIdAndTenantId(Contexto.atual().id(), Contexto.tenantId())
                .map(UsuarioDto::de).orElseThrow();
    }

    /** Primeiro acesso: troca a senha inicial (ou zerada) pela senha própria. */
    @PutMapping("/me/senha")
    public UsuarioDto definirSenha(@Valid @RequestBody NovaSenhaReq r) {
        return auth.definirSenhaPropria(Contexto.atual(), r.novaSenha());
    }

    /** Sair: o token usado deixa de valer na hora. */
    @PostMapping("/me/sair")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void sair(HttpServletRequest req) {
        String h = req.getHeader("Authorization");
        if (h != null && h.startsWith("Bearer ")) auth.sair(h.substring(7));
    }

    /** Troca da própria senha, informando a atual; devolve uma sessão nova (as outras são encerradas). */
    @PostMapping("/me/alterar-senha")
    public AuthService.Sessao alterarSenha(@Valid @RequestBody AlterarSenhaReq r) {
        return auth.alterarSenha(Contexto.atual(), r.senhaAtual(), r.novaSenha());
    }

    @PostMapping("/me/2fa/iniciar")
    public AuthService.ConfiguracaoDoisFatores iniciarDoisFatores() { return auth.iniciarDoisFatores(Contexto.atual()); }

    @PostMapping("/me/2fa/ativar")
    public UsuarioDto ativarDoisFatores(@Valid @RequestBody CodigoReq r) { return auth.ativarDoisFatores(Contexto.atual(), r.codigo()); }

    @PostMapping("/me/2fa/desativar")
    public UsuarioDto desativarDoisFatores(@Valid @RequestBody SenhaReq r) { return auth.desativarDoisFatores(Contexto.atual(), r.senha()); }
}
