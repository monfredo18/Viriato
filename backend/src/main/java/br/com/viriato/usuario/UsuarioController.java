package br.com.viriato.usuario;

import br.com.viriato.cadastro.CadastroService;
import br.com.viriato.cadastro.Setor;
import br.com.viriato.cadastro.SetorRepository;
import br.com.viriato.comum.NaoEncontradoException;
import br.com.viriato.comum.NegocioException;
import br.com.viriato.notificacao.NotificacaoRepository;
import br.com.viriato.comum.Datas;
import br.com.viriato.processo.ProcessoRepository;
import br.com.viriato.processo.ProcessoService;
import br.com.viriato.processo.StatusProcesso;
import br.com.viriato.seguranca.Contexto;
import br.com.viriato.seguranca.UsuarioLogado;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * Qualquer usuário da empresa vê a lista (nome, papel e setor), somente leitura.
 * ADMIN adiciona funcionários com qualquer papel e altera papel, situação e setor.
 * GESTOR adiciona funcionários (apenas com papel USUARIO) e ativa/desativa/apaga funcionários com
 * papel USUARIO; não altera papel nem setor de ninguém.
 * Ninguém apaga a si mesmo. Quem tem processo EM ANDAMENTO só é apagado passando esses processos para
 * herdeiros (com novo prazo); os encerrados ficam com o nome dele e restritos ao gestor do setor. Gestor só
 * apaga funcionário do próprio setor. O histórico dos processos permanece.
 */
@RestController
@RequestMapping("/api/usuarios")
public class UsuarioController {
    private final UsuarioRepository repo;
    private final SetorRepository setores;
    private final CadastroService cadastros;
    private final PasswordEncoder encoder;
    private final ProcessoRepository processos;
    private final NotificacaoRepository notificacoes;
    private final ProcessoService processoService;

    public UsuarioController(UsuarioRepository repo, SetorRepository setores, CadastroService cadastros,
                             PasswordEncoder encoder, ProcessoRepository processos, NotificacaoRepository notificacoes,
                             ProcessoService processoService) {
        this.repo = repo;
        this.setores = setores;
        this.cadastros = cadastros;
        this.encoder = encoder;
        this.processos = processos;
        this.notificacoes = notificacoes;
        this.processoService = processoService;
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
        boolean mexeEmPapelOuSetor = req.papel() != null || req.setorId() != null || Boolean.TRUE.equals(req.removerSetor());
        if (quem.papel() == Papel.GESTOR && mexeEmPapelOuSetor) {
            throw new AccessDeniedException("Gestor só pode alterar a situação do funcionário");
        }
        Usuario u = repo.findByIdAndTenantId(id, quem.tenantId()).orElseThrow(() -> new NaoEncontradoException("Usuário"));
        if (quem.papel() == Papel.GESTOR && req.ativo() != null && u.getPapel() != Papel.USUARIO) {
            // evita que um gestor tire do ar o administrador ou outro gestor
            throw new AccessDeniedException("Gestor só pode alterar a situação de funcionários com papel Usuário");
        }
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

    /** Quem pode apagar: administrador qualquer um; gestor só funcionário (papel Usuário) do próprio setor. Nunca a si mesmo. */
    private Usuario alvoApagavel(Long id, UsuarioLogado quem) {
        Usuario u = repo.findByIdAndTenantId(id, quem.tenantId()).orElseThrow(() -> new NaoEncontradoException("Usuário"));
        if (quem.papel() == Papel.GESTOR) {
            if (u.getPapel() != Papel.USUARIO) throw new AccessDeniedException("Gestor só pode apagar funcionários com papel Usuário");
            Long meuSetor = repo.findByIdAndTenantId(quem.id(), quem.tenantId()).map(Usuario::getSetorId).orElse(null);
            if (meuSetor == null || !meuSetor.equals(u.getSetorId())) {
                throw new AccessDeniedException("Gestor só pode apagar funcionários do próprio setor");
            }
        }
        if (u.getId().equals(quem.id())) throw new NegocioException("Você não pode apagar a si mesmo");
        return u;
    }

    /** O que o funcionário tem em processos; a tela usa para avisar antes de apagar. */
    public record Pendencias(String nome, long abertos, long atrasados, long concluidos, long cancelados) {}

    @GetMapping("/{id}/pendencias")
    @PreAuthorize("hasAnyRole('ADMIN','GESTOR')")
    public Pendencias pendencias(@PathVariable Long id) {
        UsuarioLogado quem = Contexto.atual();
        Usuario u = alvoApagavel(id, quem);
        Long t = quem.tenantId();
        return new Pendencias(u.getNome(),
                processos.countByTenantIdAndResponsavelIdAndStatus(t, u.getId(), StatusProcesso.ABERTO),
                processos.countByTenantIdAndResponsavelIdAndStatusAndPrazoEtapaBefore(t, u.getId(), StatusProcesso.ABERTO, Datas.hoje()),
                processos.countByTenantIdAndResponsavelIdAndStatus(t, u.getId(), StatusProcesso.CONCLUIDO),
                processos.countByTenantIdAndResponsavelIdAndStatus(t, u.getId(), StatusProcesso.CANCELADO));
    }

    /** Apaga sem processos em andamento; com processos em andamento é preciso usar {@code POST /{id}/apagar}. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN','GESTOR')")
    @Transactional
    public void apagar(@PathVariable Long id) {
        UsuarioLogado quem = Contexto.atual();
        Usuario u = alvoApagavel(id, quem);
        long responsavel = processos.countByTenantIdAndResponsavelIdAndStatus(quem.tenantId(), u.getId(), StatusProcesso.ABERTO);
        if (responsavel > 0) {
            throw new NegocioException(u.getNome() + " é responsável por " + responsavel + " processo(s) em andamento. "
                    + "Passe-os para outras pessoas ao apagar, ou apenas desative o funcionário.");
        }
        excluir(quem, u);
    }

    public record ApagarComHerdeiros(List<Long> herdeiroIds, LocalDate prazo) {}

    /**
     * Apaga o funcionário mesmo com processos: os em andamento (e atrasados) passam para os herdeiros escolhidos,
     * com um novo prazo; os concluídos e cancelados ficam com o nome dele e só o gestor do setor (e o
     * administrador) os enxerga.
     */
    @PostMapping("/{id}/apagar")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN','GESTOR')")
    @Transactional
    public void apagarPassandoProcessos(@PathVariable Long id, @RequestBody(required = false) ApagarComHerdeiros req) {
        UsuarioLogado quem = Contexto.atual();
        Usuario u = alvoApagavel(id, quem);
        processoService.transferirEmAndamento(quem, u, req == null ? null : req.herdeiroIds(), req == null ? null : req.prazo());
        excluir(quem, u);
    }

    private void excluir(UsuarioLogado quem, Usuario u) {
        processos.desvincularResponsavel(quem.tenantId(), u.getId(), u.getNome());   // encerrados guardam o nome
        notificacoes.deleteByUsuarioIdAndTenantId(u.getId(), quem.tenantId());       // avisos dele não fazem mais sentido
        repo.delete(u);
    }
}
