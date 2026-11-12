package br.com.viriato.usuario;

import br.com.viriato.seguranca.Contexto;
import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

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
    public record LoginReq(@JsonAlias({"nome", "email"}) @NotBlank String usuario, @NotBlank String senha) {}

    public record RegistroReq(@NotBlank @Size(max = 150) String empresa,
                              @NotBlank @Size(max = 150) String nome,
                              @NotBlank @Email String email,
                              @NotBlank @Size(min = 8, max = 72) String senha) {}

    public record NovaSenhaReq(@NotBlank @Size(min = 8, max = 72) String novaSenha) {}

    @PostMapping("/auth/login")
    public AuthService.Sessao login(@Valid @RequestBody LoginReq r) {
        return auth.login(r.usuario(), r.senha());
    }

    /** Desligado por padrão: a primeira conta de administrador nasce da configuração (ver README). */
    @PostMapping("/auth/registrar-empresa")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthService.Sessao registrar(@Valid @RequestBody RegistroReq r) {
        if (!cadastroAberto) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
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
}
