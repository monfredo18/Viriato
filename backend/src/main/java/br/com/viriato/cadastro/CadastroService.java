package br.com.viriato.cadastro;

import br.com.viriato.comum.NaoEncontradoException;
import br.com.viriato.comum.NegocioException;
import br.com.viriato.fluxo.Fluxo;
import br.com.viriato.fluxo.FluxoRepository;
import br.com.viriato.fluxo.FluxoService;
import br.com.viriato.processo.ProcessoRepository;
import br.com.viriato.processo.StatusProcesso;
import br.com.viriato.seguranca.UsuarioLogado;
import br.com.viriato.usuario.Papel;
import br.com.viriato.usuario.Usuario;
import br.com.viriato.usuario.UsuarioRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Cadastros do catálogo da empresa: empresas atendidas, setores e entregas. */
@Service
public class CadastroService {
    private final ClienteRepository clientes;
    private final SetorRepository setores;
    private final EntregaRepository entregas;
    private final FluxoRepository fluxos;
    private final FluxoService fluxoService;
    private final ProcessoRepository processos;
    private final UsuarioRepository usuarios;

    public CadastroService(ClienteRepository clientes, SetorRepository setores,
                           EntregaRepository entregas, FluxoRepository fluxos,
                           ProcessoRepository processos, UsuarioRepository usuarios, FluxoService fluxoService) {
        this.fluxoService = fluxoService;
        this.clientes = clientes;
        this.setores = setores;
        this.entregas = entregas;
        this.fluxos = fluxos;
        this.processos = processos;
        this.usuarios = usuarios;
    }

    // ---------- DTOs ----------
    public record ClienteDto(Long id, String numero, String nome, boolean ativo) {}
    public record EntregaDto(Long id, String nome, Long fluxoId, String fluxoNome, boolean ativo, Long setorDonoId, String setorDonoNome) {}
    public record EntregaResumo(Long id, String nome, boolean ativo) {}
    public record SetorDto(Long id, String nome, boolean ativo, List<EntregaResumo> entregas) {}

    // ---------- empresas (clientes) ----------
    @Transactional(readOnly = true)
    public List<ClienteDto> listarClientes(Long tid) {
        return clientes.findByTenantIdOrderByNome(tid).stream().map(this::dto).toList();
    }

    @Transactional
    public ClienteDto criarCliente(Long tid, String numero, String nome) {
        String n = nome.trim();
        String num = numero.trim();
        if (clientes.existsByTenantIdAndNomeIgnoreCase(tid, n)) throw new NegocioException("Já existe uma empresa com esse nome");
        if (clientes.existsByTenantIdAndNumero(tid, num)) throw new NegocioException("Já existe uma empresa com o número " + num);
        Cliente c = new Cliente();
        c.setTenantId(tid);
        c.setNumero(num);
        c.setNome(n);
        return dto(clientes.save(c));
    }

    @Transactional
    public ClienteDto atualizarCliente(Long tid, Long id, String numero, String nome, Boolean ativo) {
        Cliente c = clientes.findByIdAndTenantId(id, tid).orElseThrow(() -> new NaoEncontradoException("Empresa"));
        if (nome != null && !nome.isBlank() && !nome.trim().equalsIgnoreCase(c.getNome())) {
            if (clientes.existsByTenantIdAndNomeIgnoreCase(tid, nome.trim())) throw new NegocioException("Já existe uma empresa com esse nome");
            c.setNome(nome.trim());
        }
        if (numero != null && !numero.isBlank() && !numero.trim().equals(c.getNumero())) {
            String num = numero.trim();
            if (clientes.existsByTenantIdAndNumero(tid, num)) throw new NegocioException("Já existe uma empresa com o número " + num);
            c.setNumero(num);
            processos.atualizarNumeroDaEmpresa(tid, id, num);     // o número do processo acompanha o da empresa
            c = clientes.findByIdAndTenantId(id, tid).orElseThrow();
            c.setNumero(num);
        }
        if (ativo != null) c.setAtivo(ativo);
        return dto(clientes.save(c));
    }

    // ---------- entregas ----------
    /** Administrador vê todas; os demais veem as gerais e as exclusivas do próprio setor (nunca as de outros setores). */
    @Transactional(readOnly = true)
    public List<EntregaDto> listarEntregas(UsuarioLogado quem) {
        Long tid = quem.tenantId();
        Map<Long, String> nomesFluxo = nomesDosFluxos(tid);
        Long meuSetor = quem.papel() == Papel.ADMIN ? null : setorDe(quem);
        Map<Long, String> nomesSetor = nomesDosSetores(tid);
        return entregas.findByTenantIdOrderByNome(tid).stream()
                .filter(e -> quem.papel() == Papel.ADMIN || visivelParaSetor(e, meuSetor))
                .map(e -> dto(e, nomesFluxo, nomesSetor)).toList();
    }

    /**
     * Administrador: entrega geral (qualquer setor pode fazer, conforme a lista de cada setor). Gestor: a entrega nasce
     * exclusiva do setor dele e já entra na lista de entregas desse setor.
     */
    @Transactional
    public EntregaDto criarEntrega(UsuarioLogado quem, String nome, Long fluxoId, List<FluxoService.EtapaReq> etapas) {
        Long tid = quem.tenantId();
        String n = nome.trim();
        Long dono = null;
        if (quem.papel() != Papel.ADMIN) {
            dono = setorDe(quem);
            if (dono == null) throw new NegocioException("Seu usuário não tem setor definido. Peça ao administrador para definir o seu setor.");
        }
        exigirNomeLivre(tid, n, dono, null);
        Fluxo f;
        if (fluxoId != null) {
            f = fluxoDaEmpresa(tid, fluxoId);
            if (quem.papel() != Papel.ADMIN) throw new org.springframework.security.access.AccessDeniedException("O fluxo da sua entrega é criado junto com ela");
        } else {   // a entrega ganha um fluxo só dela, com o nome dela
            var novo = etapas == null || etapas.isEmpty()
                    ? fluxoService.criarFluxoPadrao(tid, n)
                    : fluxoService.criar(tid, n, etapas, null);
            f = fluxoDaEmpresa(tid, novo.id());
        }
        Entrega e = new Entrega();
        e.setTenantId(tid);
        e.setNome(n);
        e.setFluxoId(f.getId());
        e.setSetorDonoId(dono);
        e = entregas.save(e);
        if (dono != null) {
            Setor s = setores.findByIdAndTenantId(dono, tid).orElseThrow();
            s.getEntregas().add(e);
            setores.save(s);
        }
        return dto(e, Map.of(f.getId(), f.getNome()), nomesDosSetores(tid));
    }

    @Transactional
    public EntregaDto atualizarEntrega(UsuarioLogado quem, Long id, String nome, Long fluxoId, Boolean ativo) {
        Long tid = quem.tenantId();
        Entrega e = entregaQueEleMexe(quem, id);
        if (nome != null && !nome.isBlank() && !nome.trim().equalsIgnoreCase(e.getNome())) {
            exigirNomeLivre(tid, nome.trim(), e.getSetorDonoId(), e.getId());
            e.setNome(nome.trim());
        }
        if (fluxoId != null) e.setFluxoId(fluxoDaEmpresa(tid, fluxoId).getId());
        if (ativo != null) e.setAtivo(ativo);
        return dto(entregas.save(e), nomesDosFluxos(tid), nomesDosSetores(tid));
    }

    // ---------- setores ----------
    /** As entregas exclusivas de outro setor não aparecem para quem não é administrador. */
    @Transactional(readOnly = true)
    public List<SetorDto> listarSetores(UsuarioLogado quem) {
        Long meuSetor = quem.papel() == Papel.ADMIN ? null : setorDe(quem);
        return setores.findByTenantIdOrderByNome(quem.tenantId()).stream()
                .map(s -> dto(s, quem.papel() == Papel.ADMIN ? null : meuSetor, quem.papel() != Papel.ADMIN)).toList();
    }

    @Transactional
    public SetorDto criarSetor(Long tid, String nome, List<Long> entregaIds) {
        String n = nome.trim();
        if (setores.existsByTenantIdAndNomeIgnoreCase(tid, n)) throw new NegocioException("Já existe um setor com esse nome");
        Setor s = new Setor();
        s.setTenantId(tid);
        s.setNome(n);
        s = setores.save(s);
        s.setEntregas(entregasParaOSetor(tid, s.getId(), entregaIds, Set.of()));
        return dto(setores.save(s));
    }

    @Transactional
    public SetorDto atualizarSetor(Long tid, Long id, String nome, Boolean ativo, List<Long> entregaIds) {
        Setor s = setores.findByIdAndTenantId(id, tid).orElseThrow(() -> new NaoEncontradoException("Setor"));
        if (nome != null && !nome.isBlank() && !nome.trim().equalsIgnoreCase(s.getNome())) {
            if (setores.existsByTenantIdAndNomeIgnoreCase(tid, nome.trim())) throw new NegocioException("Já existe um setor com esse nome");
            s.setNome(nome.trim());
        }
        if (ativo != null) s.setAtivo(ativo);
        if (entregaIds != null) {
            // as entregas exclusivas deste setor continuam nele, mesmo que a lista enviada não as traga
            Set<Entrega> exclusivas = s.getEntregas().stream().filter(e -> id.equals(e.getSetorDonoId())).collect(Collectors.toSet());
            s.setEntregas(entregasParaOSetor(tid, id, entregaIds, exclusivas));
        }
        return dto(setores.save(s));
    }

    // ---------- apagar ----------
    // Regra: nunca apaga processos. Só processo EM ANDAMENTO impede apagar; os já concluídos ou
    // cancelados continuam existindo, apenas sem o vínculo com o item apagado.

    @Transactional
    public void apagarCliente(Long tid, Long id) {
        Cliente c = clientes.findByIdAndTenantId(id, tid).orElseThrow(() -> new NaoEncontradoException("Empresa"));
        exigirSemProcessosEmAndamento(processos.countByTenantIdAndClienteIdAndStatus(tid, id, StatusProcesso.ABERTO),
                "a empresa \"" + c.getNome() + "\"");
        processos.desvincularCliente(tid, id);
        clientes.delete(c);
    }

    @Transactional
    public void apagarEntrega(UsuarioLogado quem, Long id) {
        apagarEntregaDe(quem.tenantId(), entregaQueEleMexe(quem, id));
    }

    private void apagarEntregaDe(Long tid, Entrega e) {
        Long id = e.getId();
        exigirSemProcessosEmAndamento(processos.countByTenantIdAndEntregaIdAndStatus(tid, id, StatusProcesso.ABERTO),
                "a entrega \"" + e.getNome() + "\"");
        processos.desvincularEntrega(tid, id);
        e = entregas.findByIdAndTenantId(id, tid).orElseThrow();
        // tira a entrega da lista de cada setor que a fazia
        for (Setor s : setores.findByTenantIdOrderByNome(tid)) {
            if (s.getEntregas().remove(e)) setores.save(s);
        }
        Long fluxoId = e.getFluxoId();
        entregas.delete(e);
        entregas.flush();
        // o fluxo pertence à entrega: sai junto, a não ser que outra entrega (de dados antigos) ainda o use
        if (fluxoId != null && entregas.findByTenantIdAndFluxoIdOrderByNome(tid, fluxoId).isEmpty()) {
            fluxoService.apagar(fluxoId, tid);
        }
    }

    /** Apagar o setor deixa seus funcionários sem setor (eles continuam existindo). */
    @Transactional
    public void apagarSetor(Long tid, Long id) {
        Setor s = setores.findByIdAndTenantId(id, tid).orElseThrow(() -> new NaoEncontradoException("Setor"));
        exigirSemProcessosEmAndamento(processos.countByTenantIdAndSetorIdAndStatus(tid, id, StatusProcesso.ABERTO),
                "o setor \"" + s.getNome() + "\"");
        for (Entrega exclusiva : entregas.findByTenantIdAndSetorDonoId(tid, id)) apagarEntregaDe(tid, exclusiva);   // as entregas exclusivas vão junto
        processos.desvincularSetor(tid, id);
        s = setores.findByIdAndTenantId(id, tid).orElseThrow();
        for (Usuario u : usuarios.findByTenantIdAndSetorId(tid, id)) {
            u.setSetorId(null);
            usuarios.save(u);
        }
        usuarios.flush();
        setores.delete(s);
    }

    private void exigirSemProcessosEmAndamento(long quantos, String oQue) {
        if (quantos > 0) {
            throw new NegocioException("Não é possível apagar " + oQue + ": há " + quantos
                    + " processo(s) em andamento ligado(s) a este cadastro. Conclua ou cancele esses processos, "
                    + "ou desative o cadastro em vez de apagar.");
        }
    }

    // ---------- consultas usadas por outros módulos ----------
    public Cliente clienteAtivo(Long tid, Long id) {
        return clientes.findByIdAndTenantId(id, tid).filter(Cliente::isAtivo)
                .orElseThrow(() -> new NegocioException("Empresa inválida ou inativa"));
    }

    public Setor setorAtivo(Long tid, Long id) {
        return setores.findByIdAndTenantId(id, tid).filter(Setor::isAtivo)
                .orElseThrow(() -> new NegocioException("Setor inválido ou inativo"));
    }

    /** Valida que a entrega existe, está ativa e é uma das que o setor pode fazer. */
    @Transactional(readOnly = true)
    public Entrega entregaDoSetor(Long tid, Setor setor, Long entregaId) {
        Entrega e = entregas.findByIdAndTenantId(entregaId, tid).filter(Entrega::isAtivo)
                .orElseThrow(() -> new NegocioException("Entrega inválida ou inativa"));
        Setor s = setores.findByIdAndTenantId(setor.getId(), tid).orElseThrow();
        if (e.getSetorDonoId() != null && !e.getSetorDonoId().equals(s.getId())) {
            throw new NegocioException("Entrega inválida ou inativa");   // exclusiva de outro setor: nem confirma que existe
        }
        if (s.getEntregas().stream().noneMatch(x -> x.getId().equals(e.getId()))) {
            throw new NegocioException("O setor " + s.getNome() + " não faz a entrega \"" + e.getNome() + "\"");
        }
        return e;
    }

    @Transactional
    public void criarPadroes(Long tid, Long fluxoPadraoId) {
        Entrega e = new Entrega();
        e.setTenantId(tid);
        e.setNome("Atendimento geral");
        e.setFluxoId(fluxoPadraoId);
        e = entregas.save(e);
        Setor s = new Setor();
        s.setTenantId(tid);
        s.setNome("Geral");
        s.setEntregas(new HashSet<>(Set.of(e)));
        setores.save(s);
    }

    // ---------- auxiliares ----------
    private Fluxo fluxoDaEmpresa(Long tid, Long fluxoId) {
        return fluxos.findByIdAndTenantId(fluxoId, tid).orElseThrow(() -> new NaoEncontradoException("Fluxo"));
    }

    /** Lista do setor: entregas gerais e as exclusivas dele; entrega exclusiva de outro setor não entra. */
    private Set<Entrega> entregasParaOSetor(Long tid, Long setorId, List<Long> ids, Set<Entrega> manter) {
        Set<Entrega> resultado = new HashSet<>(manter);
        if (ids == null || ids.isEmpty()) return resultado;
        Set<Long> unicos = new HashSet<>(ids);
        List<Entrega> achadas = entregas.findByIdInAndTenantId(unicos, tid);
        if (achadas.size() != unicos.size()) throw new NegocioException("Entrega inválida na lista do setor");
        for (Entrega e : achadas) {
            if (e.getSetorDonoId() != null && !e.getSetorDonoId().equals(setorId)) {
                throw new NegocioException("A entrega \"" + e.getNome() + "\" é exclusiva de outro setor e não pode ser usada aqui");
            }
            resultado.add(e);
        }
        return resultado;
    }

    private Long setorDe(UsuarioLogado quem) {
        return usuarios.findByIdAndTenantId(quem.id(), quem.tenantId()).map(Usuario::getSetorId).orElse(null);
    }

    private static boolean visivelParaSetor(Entrega e, Long setorId) {
        return e.getSetorDonoId() == null || e.getSetorDonoId().equals(setorId);
    }

    /** Administrador mexe em qualquer entrega; gestor só nas exclusivas do próprio setor (as gerais e as de outros setores ele nem vê). */
    private Entrega entregaQueEleMexe(UsuarioLogado quem, Long id) {
        Entrega e = entregas.findByIdAndTenantId(id, quem.tenantId()).orElseThrow(() -> new NaoEncontradoException("Entrega"));
        if (quem.papel() == Papel.ADMIN) return e;
        Long meuSetor = setorDe(quem);
        if (e.getSetorDonoId() != null && !e.getSetorDonoId().equals(meuSetor)) throw new NaoEncontradoException("Entrega");
        if (e.getSetorDonoId() == null) throw new org.springframework.security.access.AccessDeniedException("Só o administrador altera entregas gerais");
        return e;
    }

    /** O nome da entrega é único na empresa (também vale contra as exclusivas de outros setores, pois o banco garante isso). */
    private void exigirNomeLivre(Long tid, String nome, Long dono, Long ignorarId) {
        boolean repetido = entregas.findByTenantIdOrderByNome(tid).stream()
                .anyMatch(e -> !e.getId().equals(ignorarId) && e.getNome().equalsIgnoreCase(nome));
        if (repetido) throw new NegocioException("Já existe uma entrega com esse nome. Os nomes de entrega são únicos na empresa.");
    }

    private Map<Long, String> nomesDosSetores(Long tid) {
        return setores.findByTenantIdOrderByNome(tid).stream().collect(Collectors.toMap(Setor::getId, Setor::getNome));
    }

    private Map<Long, String> nomesDosFluxos(Long tid) {
        return fluxos.findByTenantIdOrderByNome(tid).stream().collect(Collectors.toMap(Fluxo::getId, Fluxo::getNome));
    }

    private ClienteDto dto(Cliente c) { return new ClienteDto(c.getId(), c.getNumero(), c.getNome(), c.isAtivo()); }

    private EntregaDto dto(Entrega e, Map<Long, String> nomesFluxo, Map<Long, String> nomesSetor) {
        return new EntregaDto(e.getId(), e.getNome(), e.getFluxoId(), nomesFluxo.get(e.getFluxoId()), e.isAtivo(),
                e.getSetorDonoId(), e.getSetorDonoId() == null ? null : nomesSetor.get(e.getSetorDonoId()));
    }

    private SetorDto dto(Setor s) { return dto(s, null, false); }

    /** {@code soVisiveisPara}: quando {@code filtrar}, tira as entregas exclusivas de outros setores. */
    private SetorDto dto(Setor s, Long soVisiveisPara, boolean filtrar) {
        return new SetorDto(s.getId(), s.getNome(), s.isAtivo(),
                s.getEntregas().stream()
                        .filter(e -> !filtrar || visivelParaSetor(e, soVisiveisPara))
                        .sorted((a, b) -> a.getNome().compareToIgnoreCase(b.getNome()))
                        .map(e -> new EntregaResumo(e.getId(), e.getNome(), e.isAtivo())).toList());
    }
}
