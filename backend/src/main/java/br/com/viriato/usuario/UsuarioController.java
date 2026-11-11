package br.com.viriato.usuario;

import br.com.viriato.cadastro.CadastroService;
import br.com.viriato.cadastro.Setor;
import br.com.viriato.cadastro.SetorRepository;
import br.com.viriato.comum.NaoEncontradoException;
import br.com.viriato.comum.NegocioException;
import br.com.viriato.seguranca.Contexto;
import br.com.viriato.seguranca.UsuarioLogado;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

/**
 * Qualquer usuário da empresa vê a lista (nome, papel e setor), somente leitura.
 * ADMIN adiciona funcionários com qualquer papel e altera papel, situação e setor.
 * GESTOR adiciona funcionários, mas apenas com papel USUARIO, e pode ajustar o setor.
 */
@RestController
@RequestMapping("/api/usuarios")
public class UsuarioController {
    private final UsuarioRepository repo;
    private final SetorRepository setores;
    private final CadastroService cadastros;
    private final PasswordEncoder encoder;

    public UsuarioController(UsuarioRepository repo, SetorRepository setores, CadastroService cadastros,
                             PasswordEncoder encoder) {
        this.repo = repo;
        this.setores = setores;
        this.cadastros = cadastros;
        this.encoder = encoder;
    }

    public record NovoUsuario(@NotBlank @Size(max = 150) String nome,
                              @NotBlank @Email String email,
                              @NotBlank @Size(min = 8, max = 72) String senha,
                              @NotNull Papel papel,
                              Long setorId) {}

    /** {@code removerSetor=true} limpa o setor; {@code setorId} define um novo. */
    public record AtualizaUsuario(Papel papel, Boolean ativo, Long setorId, Boolean removerSetor) {}

    @GetMapping
    public List<UsuarioDto> listar() {
        Long tid = Contexto.tenantId();
        Map<Long, String> nomesSetor = setores.findByTenantIdOrderByNome(tid).stream()
                .collect(Collectors.toMap(Setor::getId, Setor::getNome));
        return repo.findByTenantIdOrderByNome(tid).stream()
                .map(u -> UsuarioDto.de(u, u.getSetorId() == null ? null : nomesSetor.get(u.getSetorId())))
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','GESTOR')")
    public UsuarioDto criar(@Valid @RequestBody NovoUsuario req) {
        UsuarioLogado quem = Contexto.atual();
        if (quem.papel() == Papel.GESTOR && req.papel() != Papel.USUARIO) {
            throw new AccessDeniedException("Gestor só pode adicionar funcionários com papel Usuário");
        }
        if (repo.existsByEmailIgnoreCase(req.email())) throw new NegocioException("E-mail já cadastrado");
        Setor setor = req.setorId() == null ? null : cadastros.setorAtivo(quem.tenantId(), req.setorId());

        Usuario u = new Usuario();
        u.setTenantId(quem.tenantId());
        u.setNome(req.nome().trim());
        u.setEmail(req.email().trim().toLowerCase());
        u.setSenhaHash(encoder.encode(req.senha()));
        u.setPapel(req.papel());
        u.setSetorId(setor == null ? null : setor.getId());
        return UsuarioDto.de(repo.save(u), setor == null ? null : setor.getNome());
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','GESTOR')")
    public UsuarioDto atualizar(@PathVariable Long id, @RequestBody AtualizaUsuario req) {
        UsuarioLogado quem = Contexto.atual();
        boolean mexeEmPapelOuSituacao = req.papel() != null || req.ativo() != null;
        if (quem.papel() == Papel.GESTOR && mexeEmPapelOuSituacao) {
            throw new AccessDeniedException("Gestor só pode ajustar o setor do funcionário");
        }
        Usuario u = repo.findByIdAndTenantId(id, quem.tenantId()).orElseThrow(() -> new NaoEncontradoException("Usuário"));
        boolean ehEle = u.getId().equals(quem.id());
        if (ehEle && (Boolean.FALSE.equals(req.ativo()) || (req.papel() != null && req.papel() != Papel.ADMIN))) {
            throw new NegocioException("Você não pode desativar ou rebaixar a si mesmo");
        }
        if (req.papel() != null) u.setPapel(req.papel());
        if (req.ativo() != null) u.setAtivo(req.ativo());
        if (Boolean.TRUE.equals(req.removerSetor())) {
            u.setSetorId(null);
        } else if (req.setorId() != null) {
            u.setSetorId(cadastros.setorAtivo(quem.tenantId(), req.setorId()).getId());
        }
        Usuario salvo = repo.save(u);
        String nomeSetor = salvo.getSetorId() == null ? null
                : setores.findByIdAndTenantId(salvo.getSetorId(), quem.tenantId()).map(Setor::getNome).orElse(null);
        return UsuarioDto.de(salvo, nomeSetor);
    }
}
