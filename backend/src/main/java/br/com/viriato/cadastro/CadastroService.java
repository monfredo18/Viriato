package br.com.viriato.cadastro;

import br.com.viriato.comum.NaoEncontradoException;
import br.com.viriato.comum.NegocioException;
import br.com.viriato.fluxo.Fluxo;
import br.com.viriato.fluxo.FluxoRepository;
import br.com.viriato.processo.ProcessoRepository;
import br.com.viriato.processo.StatusProcesso;
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
    private final ProcessoRepository processos;
    private final UsuarioRepository usuarios;

    public CadastroService(ClienteRepository clientes, SetorRepository setores,
                           EntregaRepository entregas, FluxoRepository fluxos,
                           ProcessoRepository processos, UsuarioRepository usuarios) {
        this.clientes = clientes;
        this.setores = setores;
        this.entregas = entregas;
        this.fluxos = fluxos;
        this.processos = processos;
        this.usuarios = usuarios;
    }

    // ---------- DTOs ----------
    public record ClienteDto(Long id, String numero, String nome, boolean ativo, String email) {}
    public record EntregaDto(Long id, String nome, Long fluxoId, String fluxoNome, boolean ativo) {}
    public record EntregaResumo(Long id, String nome, boolean ativo) {}
    public record SetorDto(Long id, String nome, boolean ativo, List<EntregaResumo> entregas) {}

    // ---------- empresas (clientes) ----------
    @Transactional(readOnly = true)
    public List<ClienteDto> listarClientes(Long tid) {
        return clientes.findByTenantIdOrderByNome(tid).stream().map(this::dto).toList();
    }

    @Transactional
    public ClienteDto criarCliente(Long tid, String numero, String nome, String email) {
        String n = nome.trim();
        String num = numero.trim();
        if (clientes.existsByTenantIdAndNomeIgnoreCase(tid, n)) throw new NegocioException("Já existe uma empresa com esse nome");
        if (clientes.existsByTenantIdAndNumero(tid, num)) throw new NegocioException("Já existe uma empresa com o número " + num);
        Cliente c = new Cliente();
        c.setTenantId(tid);
        c.setNumero(num);
        c.setNome(n);
        c.setEmail(limparEmail(email));
        return dto(clientes.save(c));
    }

    @Transactional
    public ClienteDto atualizarCliente(Long tid, Long id, String numero, String nome, Boolean ativo, String email) {
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
        if (email != null) c.setEmail(limparEmail(email));   // null = não muda; "" = apaga
        return dto(clientes.save(c));
    }

    // ---------- entregas ----------
    @Transactional(readOnly = true)
    public List<EntregaDto> listarEntregas(Long tid) {
        Map<Long, String> nomesFluxo = nomesDosFluxos(tid);
        return entregas.findByTenantIdOrderByNome(tid).stream().map(e -> dto(e, nomesFluxo)).toList();
    }

    @Transactional
    public EntregaDto criarEntrega(Long tid, String nome, Long fluxoId) {
        String n = nome.trim();
        if (entregas.existsByTenantIdAndNomeIgnoreCase(tid, n)) throw new NegocioException("Já existe uma entrega com esse nome");
        Fluxo f = fluxoDaEmpresa(tid, fluxoId);
        Entrega e = new Entrega();
        e.setTenantId(tid);
        e.setNome(n);
        e.setFluxoId(f.getId());
        return dto(entregas.save(e), Map.of(f.getId(), f.getNome()));
    }

    @Transactional
    public EntregaDto atualizarEntrega(Long tid, Long id, String nome, Long fluxoId, Boolean ativo) {
        Entrega e = entregas.findByIdAndTenantId(id, tid).orElseThrow(() -> new NaoEncontradoException("Entrega"));
        if (nome != null && !nome.isBlank() && !nome.trim().equalsIgnoreCase(e.getNome())) {
            if (entregas.existsByTenantIdAndNomeIgnoreCase(tid, nome.trim())) throw new NegocioException("Já existe uma entrega com esse nome");
            e.setNome(nome.trim());
        }
        if (fluxoId != null) e.setFluxoId(fluxoDaEmpresa(tid, fluxoId).getId());
        if (ativo != null) e.setAtivo(ativo);
        return dto(entregas.save(e), nomesDosFluxos(tid));
    }

    // ---------- setores ----------
    @Transactional(readOnly = true)
    public List<SetorDto> listarSetores(Long tid) {
        return setores.findByTenantIdOrderByNome(tid).stream().map(this::dto).toList();
    }

    @Transactional
    public SetorDto criarSetor(Long tid, String nome, List<Long> entregaIds) {
        String n = nome.trim();
        if (setores.existsByTenantIdAndNomeIgnoreCase(tid, n)) throw new NegocioException("Já existe um setor com esse nome");
        Setor s = new Setor();
        s.setTenantId(tid);
        s.setNome(n);
        s.setEntregas(entregasDaEmpresa(tid, entregaIds));
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
        if (entregaIds != null) s.setEntregas(entregasDaEmpresa(tid, entregaIds));
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
    public void apagarEntrega(Long tid, Long id) {
        Entrega e = entregas.findByIdAndTenantId(id, tid).orElseThrow(() -> new NaoEncontradoException("Entrega"));
        exigirSemProcessosEmAndamento(processos.countByTenantIdAndEntregaIdAndStatus(tid, id, StatusProcesso.ABERTO),
                "a entrega \"" + e.getNome() + "\"");
        processos.desvincularEntrega(tid, id);
        e = entregas.findByIdAndTenantId(id, tid).orElseThrow();
        // tira a entrega da lista de cada setor que a fazia
        for (Setor s : setores.findByTenantIdOrderByNome(tid)) {
            if (s.getEntregas().remove(e)) setores.save(s);
        }
        entregas.delete(e);
    }

    /** Apagar o setor deixa seus funcionários sem setor (eles continuam existindo). */
    @Transactional
    public void apagarSetor(Long tid, Long id) {
        Setor s = setores.findByIdAndTenantId(id, tid).orElseThrow(() -> new NaoEncontradoException("Setor"));
        exigirSemProcessosEmAndamento(processos.countByTenantIdAndSetorIdAndStatus(tid, id, StatusProcesso.ABERTO),
                "o setor \"" + s.getNome() + "\"");
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

    private Set<Entrega> entregasDaEmpresa(Long tid, List<Long> ids) {
        if (ids == null || ids.isEmpty()) return new HashSet<>();
        Set<Long> unicos = new HashSet<>(ids);
        List<Entrega> achadas = entregas.findByIdInAndTenantId(unicos, tid);
        if (achadas.size() != unicos.size()) throw new NegocioException("Entrega inválida na lista do setor");
        return new HashSet<>(achadas);
    }

    private Map<Long, String> nomesDosFluxos(Long tid) {
        return fluxos.findByTenantIdOrderByNome(tid).stream().collect(Collectors.toMap(Fluxo::getId, Fluxo::getNome));
    }

    private static String limparEmail(String email) { return email == null || email.isBlank() ? null : email.trim().toLowerCase(); }

    private ClienteDto dto(Cliente c) { return new ClienteDto(c.getId(), c.getNumero(), c.getNome(), c.isAtivo(), c.getEmail()); }

    private EntregaDto dto(Entrega e, Map<Long, String> nomesFluxo) {
        return new EntregaDto(e.getId(), e.getNome(), e.getFluxoId(), nomesFluxo.get(e.getFluxoId()), e.isAtivo());
    }

    private SetorDto dto(Setor s) {
        return new SetorDto(s.getId(), s.getNome(), s.isAtivo(),
                s.getEntregas().stream()
                        .sorted((a, b) -> a.getNome().compareToIgnoreCase(b.getNome()))
                        .map(e -> new EntregaResumo(e.getId(), e.getNome(), e.isAtivo())).toList());
    }
}
