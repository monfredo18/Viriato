package br.com.viriato.usuario;

import br.com.viriato.seguranca.Contexto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class AuthController {
    private final AuthService auth;
    private final UsuarioRepository usuarios;

    public AuthController(AuthService auth, UsuarioRepository usuarios) {
        this.auth = auth;
        this.usuarios = usuarios;
    }

    public record LoginReq(@NotBlank @Email String email, @NotBlank String senha) {}

    public record RegistroReq(@NotBlank @Size(max = 150) String empresa,
                              @NotBlank @Size(max = 150) String nome,
                              @NotBlank @Email String email,
                              @NotBlank @Size(min = 8, max = 72) String senha) {}

    @PostMapping("/auth/login")
    public AuthService.Sessao login(@Valid @RequestBody LoginReq r) {
        return auth.login(r.email(), r.senha());
    }

    @PostMapping("/auth/registrar-empresa")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthService.Sessao registrar(@Valid @RequestBody RegistroReq r) {
        return auth.registrarEmpresa(r.empresa(), r.nome(), r.email(), r.senha());
    }

    @GetMapping("/me")
    public UsuarioDto me() {
        return usuarios.findByIdAndTenantId(Contexto.atual().id(), Contexto.tenantId())
                .map(UsuarioDto::de).orElseThrow();
    }
}
