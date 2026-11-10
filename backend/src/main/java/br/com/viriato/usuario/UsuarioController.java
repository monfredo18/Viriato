package br.com.viriato.usuario;

import br.com.viriato.comum.NaoEncontradoException;
import br.com.viriato.comum.NegocioException;
import br.com.viriato.seguranca.Contexto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/usuarios")
public class UsuarioController {
    private final UsuarioRepository repo;
    private final PasswordEncoder encoder;

    public UsuarioController(UsuarioRepository repo, PasswordEncoder encoder) {
        this.repo = repo;
        this.encoder = encoder;
    }

    public record NovoUsuario(@NotBlank @Size(max = 150) String nome,
                              @NotBlank @Email String email,
                              @NotBlank @Size(min = 8, max = 72) String senha,
                              @NotNull Papel papel) {}

    public record AtualizaUsuario(Papel papel, Boolean ativo) {}

    /** Qualquer usuário autenticado pode listar colegas (necessário para atribuir responsável). */
    @GetMapping
    public List<UsuarioDto> listar() {
        return repo.findByTenantIdOrderByNome(Contexto.tenantId()).stream().map(UsuarioDto::de).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public UsuarioDto criar(@Valid @RequestBody NovoUsuario req) {
        if (repo.existsByEmailIgnoreCase(req.email())) throw new NegocioException("E-mail já cadastrado");
        Usuario u = new Usuario();
        u.setTenantId(Contexto.tenantId());
        u.setNome(req.nome());
        u.setEmail(req.email().toLowerCase());
        u.setSenhaHash(encoder.encode(req.senha()));
        u.setPapel(req.papel());
        return UsuarioDto.de(repo.save(u));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public UsuarioDto atualizar(@PathVariable Long id, @RequestBody AtualizaUsuario req) {
        Usuario u = repo.findByIdAndTenantId(id, Contexto.tenantId()).orElseThrow(() -> new NaoEncontradoException("Usuário"));
        boolean ehEle = u.getId().equals(Contexto.atual().id());
        if (ehEle && (Boolean.FALSE.equals(req.ativo()) || (req.papel() != null && req.papel() != Papel.ADMIN))) {
            throw new NegocioException("Você não pode desativar ou rebaixar a si mesmo");
        }
        if (req.papel() != null) u.setPapel(req.papel());
        if (req.ativo() != null) u.setAtivo(req.ativo());
        return UsuarioDto.de(repo.save(u));
    }
}
