package br.com.viriato.processo;

import br.com.viriato.cadastro.ClienteRepository;
import br.com.viriato.comum.NaoEncontradoException;
import br.com.viriato.comum.NegocioException;
import br.com.viriato.fluxo.EtapaRepository;
import br.com.viriato.seguranca.UsuarioLogado;
import br.com.viriato.usuario.Papel;
import br.com.viriato.usuario.UsuarioRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Trava interdepartamental: um processo só avança quando todos os processos dos quais depende estiverem CONCLUÍDOS
 * (por exemplo, a apuração fiscal depende da importação de XMLs). Só administrador e gestor criam ou removem dependências.
 */
@Service
public class DependenciaService {
    /** Dados mínimos do processo bloqueador (pode ser de outro setor). */
    public record DependenciaDto(Long id, Long predecessorId, String numero, String titulo, String empresa, StatusProcesso status, String etapaAtual, boolean resolvida) {}

    private final ProcessoDependenciaRepository deps;
    private final ProcessoRepository processos;
    private final ClienteRepository clientes;
    private final EtapaRepository etapas;
    private final UsuarioRepository usuarios;

    public DependenciaService(ProcessoDependenciaRepository deps, ProcessoRepository processos, ClienteRepository clientes, EtapaRepository etapas, UsuarioRepository usuarios) {
        this.deps = deps;
        this.processos = processos;
        this.clientes = clientes;
        this.etapas = etapas;
        this.usuarios = usuarios;
    }

    @Transactional(readOnly = true)
    public List<DependenciaDto> doProcesso(Processo p) {
        return deps.findByProcessoIdAndTenantId(p.getId(), p.getTenantId()).stream().map(d -> dto(d, p.getTenantId())).filter(x -> x != null).toList();
    }

    /** Impede o avanço enquanto houver bloqueador não concluído. */
    @Transactional(readOnly = true)
    public void exigirResolvidas(Processo p) {
        List<DependenciaDto> pendentes = doProcesso(p).stream().filter(d -> !d.resolvida()).toList();
        if (pendentes.isEmpty()) return;
        String lista = pendentes.stream().map(d -> d.titulo() + " (" + d.numero() + ", " + (d.status() == StatusProcesso.CANCELADO ? "cancelado" : d.etapaAtual() == null ? "em andamento" : d.etapaAtual()) + ")").limit(3)
                .reduce((a, b) -> a + "; " + b).orElse("");
        throw new NegocioException("Este processo depende de outro que ainda não foi concluído: " + lista + (pendentes.size() > 3 ? " e mais " + (pendentes.size() - 3) : ""));
    }

    @Transactional
    public DependenciaDto vincular(UsuarioLogado quem, Long processoId, Long predecessorId) {
        Processo p = doAlcance(quem, processoId);
        Processo pred = processos.findByIdAndTenantId(predecessorId, quem.tenantId()).orElseThrow(() -> new NaoEncontradoException("Processo predecessor"));
        ProcessoDependencia d = criar(p, pred);
        return dto(d, quem.tenantId());
    }

    @Transactional
    public void desvincular(UsuarioLogado quem, Long processoId, Long dependenciaId) {
        Processo p = doAlcance(quem, processoId);
        deps.delete(deps.findByIdAndProcessoIdAndTenantId(dependenciaId, p.getId(), quem.tenantId()).orElseThrow(() -> new NaoEncontradoException("Dependência")));
    }

    /** Vínculo feito pelo sistema (matriz tributária): idempotente, sem checar papel. */
    @Transactional
    public boolean vincularPorRegra(Processo p, Processo predecessor) {
        if (p.getId().equals(predecessor.getId()) || deps.existsByProcessoIdAndPredecessorId(p.getId(), predecessor.getId())) return false;
        if (alcanca(predecessor.getId(), p.getId(), p.getTenantId(), new HashSet<>())) return false;   // ciclo: ignora
        criar(p, predecessor);
        return true;
    }

    private ProcessoDependencia criar(Processo p, Processo pred) {
        if (p.getId().equals(pred.getId())) throw new NegocioException("Um processo não pode depender de si mesmo");
        if (!p.getTenantId().equals(pred.getTenantId())) throw new NaoEncontradoException("Processo predecessor");
        if (deps.existsByProcessoIdAndPredecessorId(p.getId(), pred.getId())) throw new NegocioException("Esta dependência já existe");
        if (alcanca(pred.getId(), p.getId(), p.getTenantId(), new HashSet<>())) throw new NegocioException("A dependência criaria um ciclo entre os processos");
        ProcessoDependencia d = new ProcessoDependencia();
        d.setTenantId(p.getTenantId());
        d.setProcessoId(p.getId());
        d.setPredecessorId(pred.getId());
        return deps.save(d);
    }

    /** O processo {@code origem} depende (direta ou indiretamente) de {@code alvo}? */
    private boolean alcanca(Long origem, Long alvo, Long tenantId, Set<Long> vistos) {
        if (!vistos.add(origem)) return false;
        for (ProcessoDependencia d : deps.findByProcessoIdAndTenantId(origem, tenantId)) {
            if (d.getPredecessorId().equals(alvo) || alcanca(d.getPredecessorId(), alvo, tenantId, vistos)) return true;
        }
        return false;
    }

    private Processo doAlcance(UsuarioLogado quem, Long id) {
        if (quem.papel() == Papel.USUARIO) throw new org.springframework.security.access.AccessDeniedException("Só administrador e gestor alteram dependências");
        Processo p = processos.findByIdAndTenantId(id, quem.tenantId()).orElseThrow(() -> new NaoEncontradoException("Processo"));
        if (quem.papel() == Papel.GESTOR) {
            Long meu = usuarios.setorDe(quem);
            if (meu == null || !meu.equals(p.getSetorId())) throw new NaoEncontradoException("Processo");
        }
        return p;
    }

    private DependenciaDto dto(ProcessoDependencia d, Long tenantId) {
        Processo pred = processos.findByIdAndTenantId(d.getPredecessorId(), tenantId).orElse(null);
        if (pred == null) return null;
        String etapa = pred.getEtapaAtualId() == null ? null : etapas.findById(pred.getEtapaAtualId()).map(e -> e.getNome()).orElse(null);
        String empresa = pred.getClienteId() == null ? null : clientes.findByIdAndTenantId(pred.getClienteId(), tenantId).map(c -> c.getNome()).orElse(null);
        return new DependenciaDto(d.getId(), pred.getId(), pred.getNumero(), pred.getTitulo(), empresa, pred.getStatus(), etapa, pred.getStatus() == StatusProcesso.CONCLUIDO);
    }
}
