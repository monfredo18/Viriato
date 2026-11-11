package br.com.viriato.processo;

import br.com.viriato.cadastro.CadastroService;
import br.com.viriato.cadastro.Cliente;
import br.com.viriato.cadastro.ClienteRepository;
import br.com.viriato.cadastro.Entrega;
import br.com.viriato.cadastro.EntregaRepository;
import br.com.viriato.cadastro.Setor;
import br.com.viriato.cadastro.SetorRepository;
import br.com.viriato.comum.Datas;
import br.com.viriato.comum.NaoEncontradoException;
import br.com.viriato.comum.NegocioException;
import br.com.viriato.fluxo.*;
import br.com.viriato.fluxo.FluxoService.EtapaDto;
import br.com.viriato.notificacao.NotificacaoService;
import br.com.viriato.processo.ProcessoDto.Detalhe;
import br.com.viriato.processo.ProcessoDto.HistoricoDto;
import br.com.viriato.seguranca.UsuarioLogado;
import br.com.viriato.usuario.Papel;
import br.com.viriato.usuario.Usuario;
import br.com.viriato.usuario.UsuarioRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.criteria.Predicate;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProcessoService {
    private static final TypeReference<Map<String, Object>> MAPA = new TypeReference<>() {};

    private final ProcessoRepository processos;
    private final ProcessoHistoricoRepository historicos;
    private final FluxoRepository fluxos;
    private final EtapaRepository etapas;
    private final TransicaoRepository transicoes;
    private final UsuarioRepository usuarios;
    private final NotificacaoService notificacoes;
    private final CadastroService cadastros;
    private final ClienteRepository clientes;
    private final EntregaRepository entregasRepo;
    private final SetorRepository setoresRepo;
    private final ObjectMapper json;

    public ProcessoService(ProcessoRepository processos, ProcessoHistoricoRepository historicos,
                           FluxoRepository fluxos, EtapaRepository etapas, TransicaoRepository transicoes,
                           UsuarioRepository usuarios, NotificacaoService notificacoes, CadastroService cadastros,
                           ClienteRepository clientes, EntregaRepository entregasRepo, SetorRepository setoresRepo,
                           ObjectMapper json) {
        this.processos = processos;
        this.historicos = historicos;
        this.fluxos = fluxos;
        this.etapas = etapas;
        this.transicoes = transicoes;
        this.usuarios = usuarios;
        this.notificacoes = notificacoes;
        this.cadastros = cadastros;
        this.clientes = clientes;
        this.entregasRepo = entregasRepo;
        this.setoresRepo = setoresRepo;
        this.json = json;
    }

    public record Filtro(StatusProcesso status, Long etapaId, Long responsavelId, Long clienteId, Long setorId,
                         Long entregaId, String busca, boolean atrasados) {}

    /** Dados do formulário de novo processo: empresa, entrega, setor e responsável. */
    public record NovoProcesso(Long clienteId, Long entregaId, Long setorId, Long responsavelId,
                               String descricao, Map<String, Object> camposExtras) {}

    // ---------- criação ----------

    @Transactional
    public ProcessoDto criar(UsuarioLogado quem, NovoProcesso n) {
        Long tid = quem.tenantId();
        Cliente cliente = cadastros.clienteAtivo(tid, n.clienteId());
        Setor setor = cadastros.setorAtivo(tid, n.setorId());
        Entrega entrega = cadastros.entregaDoSetor(tid, setor, n.entregaId());
        Fluxo fluxo = fluxos.findByIdAndTenantId(entrega.getFluxoId(), tid)
                .orElseThrow(() -> new NegocioException("A entrega não tem um fluxo válido"));
        if (!fluxo.isAtivo()) throw new NegocioException("O fluxo desta entrega está inativo");
        Etapa inicial = etapas.findFirstByFluxoIdAndTenantIdAndInicialTrue(fluxo.getId(), tid)
                .orElseThrow(() -> new NegocioException("Fluxo sem etapa inicial"));
        exigirUsuarioAtivo(n.responsavelId(), tid);
        Long fluxoId = fluxo.getId();
        Long responsavelId = n.responsavelId();
        String titulo = entrega.getNome() + " — " + cliente.getNome();
        if (titulo.length() > 200) titulo = titulo.substring(0, 200);
        String descricao = n.descricao();
        Map<String, Object> extras = n.camposExtras();

        Processo p = new Processo();
        p.setTenantId(tid);
        p.setNumero("TMP-" + UUID.randomUUID().toString().substring(0, 8));
        p.setTitulo(titulo);
        p.setDescricao(descricao);
        p.setClienteId(cliente.getId());
        p.setEntregaId(entrega.getId());
        p.setSetorId(setor.getId());
        p.setFluxoId(fluxoId);
        p.setEtapaAtualId(inicial.getId());
        p.setResponsavelId(responsavelId);
        p.setPrazoEtapa(calcularPrazo(inicial));
        p.setCamposExtras(serializar(extras));
        p.setCriadoPor(quem.id());
        p = processos.save(p);
        p.setNumero(String.format("%d-%06d", Datas.hoje().getYear(), p.getId()));
        p = processos.save(p);

        registrar(p, quem.id(), "CRIADO", null, inicial.getNome(), null);
        if (responsavelId != null && !responsavelId.equals(quem.id())) {
            notificacoes.notificar(tid, responsavelId, "Novo processo atribuído a você",
                    p.getNumero() + " - " + p.getTitulo(), p.getId());
        }
        return paraDto(p);
    }

    // ---------- consulta ----------

    @Transactional(readOnly = true)
    public Page<ProcessoDto> listar(Long tenantId, Filtro f, Pageable page) {
        Specification<Processo> spec = (root, q, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.equal(root.get("tenantId"), tenantId));
            if (f.status() != null) ps.add(cb.equal(root.get("status"), f.status()));
            if (f.etapaId() != null) ps.add(cb.equal(root.get("etapaAtualId"), f.etapaId()));
            if (f.responsavelId() != null) ps.add(cb.equal(root.get("responsavelId"), f.responsavelId()));
            if (f.clienteId() != null) ps.add(cb.equal(root.get("clienteId"), f.clienteId()));
            if (f.setorId() != null) ps.add(cb.equal(root.get("setorId"), f.setorId()));
            if (f.entregaId() != null) ps.add(cb.equal(root.get("entregaId"), f.entregaId()));
            if (f.atrasados()) {
                ps.add(cb.equal(root.get("status"), StatusProcesso.ABERTO));
                ps.add(cb.lessThan(root.get("prazoEtapa"), Datas.hoje()));
            }
            if (f.busca() != null && !f.busca().isBlank()) {
                String like = "%" + f.busca().trim().toLowerCase().replace("%", "") + "%";
                ps.add(cb.or(cb.like(cb.lower(root.get("titulo")), like), cb.like(cb.lower(root.get("numero")), like)));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
        Page<Processo> pagina = processos.findAll(spec, page);
        Lookups lk = lookups(pagina.getContent(), tenantId);
        return pagina.map(p -> paraDto(p, lk));
    }

    @Transactional(readOnly = true)
    public Detalhe detalhe(Long id, Long tenantId) {
        Processo p = buscar(id, tenantId);
        List<EtapaDto> proximas = proximasEtapas(p);
        Map<Long, String> nomes = usuarios.findByTenantIdOrderByNome(tenantId).stream()
                .collect(Collectors.toMap(Usuario::getId, Usuario::getNome));
        List<HistoricoDto> hist = historicos.findByProcessoIdAndTenantIdOrderByIdDesc(id, tenantId).stream()
                .map(h -> new HistoricoDto(h.getId(), h.getAcao(), nomes.get(h.getUsuarioId()), h.getDeEtapa(),
                        h.getParaEtapa(), h.getComentario(), h.getCriadoEm()))
                .toList();
        return new Detalhe(paraDto(p), proximas, hist);
    }

    // ---------- operações ----------

    @Transactional
    public ProcessoDto avancar(UsuarioLogado quem, Long id, Long etapaDestinoId, String comentario) {
        Processo p = buscar(id, quem.tenantId());
        exigirAberto(p);
        exigirPodeMexer(quem, p);
        Etapa destino = etapas.findByIdAndTenantId(etapaDestinoId, quem.tenantId())
                .orElseThrow(() -> new NaoEncontradoException("Etapa"));
        if (!transicoes.existsByDeEtapaAndParaEtapaAndTenantId(p.getEtapaAtualId(), destino.getId(), quem.tenantId())) {
            throw new NegocioException("Transição não permitida pelo fluxo");
        }
        Etapa origem = etapas.findById(p.getEtapaAtualId()).orElseThrow();

        p.setEtapaAtualId(destino.getId());
        p.setPrazoAlertado(false);
        if (destino.isFinalEtapa()) {
            p.setStatus(StatusProcesso.CONCLUIDO);
            p.setPrazoEtapa(null);
        } else {
            p.setPrazoEtapa(calcularPrazo(destino));
        }
        p = processos.save(p);
        registrar(p, quem.id(), "MOVIDO", origem.getNome(), destino.getNome(), comentario);

        if (p.getResponsavelId() != null && !p.getResponsavelId().equals(quem.id())) {
            notificacoes.notificar(quem.tenantId(), p.getResponsavelId(),
                    destino.isFinalEtapa() ? "Processo concluído" : "Processo movido para " + destino.getNome(),
                    p.getNumero() + " - " + p.getTitulo(), p.getId());
        }
        return paraDto(p);
    }

    @Transactional
    public void comentar(UsuarioLogado quem, Long id, String texto) {
        Processo p = buscar(id, quem.tenantId());
        exigirPodeMexer(quem, p);
        registrar(p, quem.id(), "COMENTARIO", null, null, texto);
        if (p.getResponsavelId() != null && !p.getResponsavelId().equals(quem.id())) {
            notificacoes.notificar(quem.tenantId(), p.getResponsavelId(), "Novo comentário em " + p.getNumero(),
                    texto, p.getId());
        }
    }

    @Transactional
    public ProcessoDto atribuir(UsuarioLogado quem, Long id, Long responsavelId) {
        Processo p = buscar(id, quem.tenantId());
        exigirAberto(p);
        boolean gestor = quem.papel() != Papel.USUARIO;
        if (!gestor && !quem.id().equals(p.getResponsavelId())) {
            throw new NegocioException("Apenas o responsável atual ou um gestor pode reatribuir");
        }
        if (responsavelId != null) exigirUsuarioAtivo(responsavelId, quem.tenantId());
        p.setResponsavelId(responsavelId);
        p.setPrazoAlertado(false);
        p = processos.save(p);
        String nome = responsavelId == null ? "ninguém" : usuarios.findById(responsavelId).map(Usuario::getNome).orElse("?");
        registrar(p, quem.id(), "ATRIBUIDO", null, null, "Responsável: " + nome);
        if (responsavelId != null && !responsavelId.equals(quem.id())) {
            notificacoes.notificar(quem.tenantId(), responsavelId, "Processo atribuído a você",
                    p.getNumero() + " - " + p.getTitulo(), p.getId());
        }
        return paraDto(p);
    }

    @Transactional
    public ProcessoDto cancelar(UsuarioLogado quem, Long id, String motivo) {
        if (quem.papel() == Papel.USUARIO) throw new NegocioException("Apenas gestores podem cancelar processos");
        Processo p = buscar(id, quem.tenantId());
        exigirAberto(p);
        p.setStatus(StatusProcesso.CANCELADO);
        p.setPrazoEtapa(null);
        p = processos.save(p);
        registrar(p, quem.id(), "CANCELADO", null, null, motivo);
        return paraDto(p);
    }

    public Processo buscar(Long id, Long tenantId) {
        return processos.findByIdAndTenantId(id, tenantId).orElseThrow(() -> new NaoEncontradoException("Processo"));
    }

    // ---------- auxiliares ----------

    private void exigirAberto(Processo p) {
        if (p.getStatus() != StatusProcesso.ABERTO) throw new NegocioException("Processo não está aberto");
    }

    /**
     * Administrador e gestor mexem em qualquer processo da empresa. O usuário comum enxerga todos,
     * mas só mexe naqueles em que é o responsável.
     */
    public void exigirPodeMexer(UsuarioLogado quem, Processo p) {
        if (quem.papel() != Papel.USUARIO) return;
        if (!quem.id().equals(p.getResponsavelId())) {
            throw new NegocioException("Você só pode mexer nos processos em que é o responsável");
        }
    }

    private void exigirUsuarioAtivo(Long usuarioId, Long tenantId) {
        usuarios.findByIdAndTenantId(usuarioId, tenantId).filter(Usuario::isAtivo)
                .orElseThrow(() -> new NegocioException("Responsável inválido"));
    }

    private LocalDate calcularPrazo(Etapa e) {
        return e.getPrazoDias() == null ? null : Datas.hoje().plusDays(e.getPrazoDias());
    }

    private List<EtapaDto> proximasEtapas(Processo p) {
        if (p.getStatus() != StatusProcesso.ABERTO) return List.of();
        Set<Long> destinos = transicoes.findByFluxoIdAndTenantId(p.getFluxoId(), p.getTenantId()).stream()
                .filter(t -> t.getDeEtapa().equals(p.getEtapaAtualId()))
                .map(Transicao::getParaEtapa).collect(Collectors.toSet());
        return etapas.findByFluxoIdAndTenantIdOrderByOrdem(p.getFluxoId(), p.getTenantId()).stream()
                .filter(e -> destinos.contains(e.getId()))
                .map(e -> new EtapaDto(e.getId(), e.getNome(), e.getOrdem(), e.getPrazoDias(), e.isInicial(), e.isFinalEtapa()))
                .toList();
    }

    private void registrar(Processo p, Long usuarioId, String acao, String de, String para, String comentario) {
        ProcessoHistorico h = new ProcessoHistorico();
        h.setTenantId(p.getTenantId());
        h.setProcessoId(p.getId());
        h.setUsuarioId(usuarioId);
        h.setAcao(acao);
        h.setDeEtapa(de);
        h.setParaEtapa(para);
        h.setComentario(comentario);
        historicos.save(h);
    }

    private String serializar(Map<String, Object> extras) {
        if (extras == null || extras.isEmpty()) return null;
        try {
            return json.writeValueAsString(extras);
        } catch (JsonProcessingException e) {
            throw new NegocioException("Campos extras inválidos");
        }
    }

    private Map<String, Object> ler(String texto) {
        if (texto == null || texto.isBlank()) return Map.of();
        try {
            return json.readValue(texto, MAPA);
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

    private record Lookups(Map<Long, String> fluxos, Map<Long, Etapa> etapas, Map<Long, String> usuarios,
                           Map<Long, String> clientes, Map<Long, String> entregas, Map<Long, String> setores) {}

    private Lookups lookups(List<Processo> ps, Long tenantId) {
        Set<Long> fluxoIds = ps.stream().map(Processo::getFluxoId).collect(Collectors.toSet());
        Set<Long> etapaIds = ps.stream().map(Processo::getEtapaAtualId).collect(Collectors.toSet());
        Set<Long> userIds = ps.stream().map(Processo::getResponsavelId).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> clienteIds = ps.stream().map(Processo::getClienteId).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> entregaIds = ps.stream().map(Processo::getEntregaId).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> setorIds = ps.stream().map(Processo::getSetorId).filter(Objects::nonNull).collect(Collectors.toSet());
        return new Lookups(
                fluxos.findAllById(fluxoIds).stream().collect(Collectors.toMap(Fluxo::getId, Fluxo::getNome)),
                etapas.findAllById(etapaIds).stream().collect(Collectors.toMap(Etapa::getId, Function.identity())),
                usuarios.findAllById(userIds).stream().collect(Collectors.toMap(Usuario::getId, Usuario::getNome)),
                clientes.findAllById(clienteIds).stream().collect(Collectors.toMap(Cliente::getId, Cliente::getNome)),
                entregasRepo.findAllById(entregaIds).stream().collect(Collectors.toMap(Entrega::getId, Entrega::getNome)),
                setoresRepo.findAllById(setorIds).stream().collect(Collectors.toMap(Setor::getId, Setor::getNome)));
    }

    private static String nome(Map<Long, String> m, Long id) { return id == null ? null : m.get(id); }

    private ProcessoDto paraDto(Processo p) {
        return paraDto(p, lookups(List.of(p), p.getTenantId()));
    }

    private ProcessoDto paraDto(Processo p, Lookups lk) {
        Etapa e = lk.etapas().get(p.getEtapaAtualId());
        boolean atrasado = p.getStatus() == StatusProcesso.ABERTO && p.getPrazoEtapa() != null
                && p.getPrazoEtapa().isBefore(Datas.hoje());
        return new ProcessoDto(p.getId(), p.getNumero(), p.getTitulo(), p.getDescricao(),
                p.getClienteId(), nome(lk.clientes(), p.getClienteId()),
                p.getEntregaId(), nome(lk.entregas(), p.getEntregaId()),
                p.getSetorId(), nome(lk.setores(), p.getSetorId()),
                p.getFluxoId(), lk.fluxos().get(p.getFluxoId()),
                p.getEtapaAtualId(), e == null ? null : e.getNome(), e != null && e.isFinalEtapa(),
                p.getResponsavelId(), p.getResponsavelId() == null ? null : lk.usuarios().get(p.getResponsavelId()),
                p.getStatus(), p.getPrazoEtapa(), atrasado, ler(p.getCamposExtras()),
                p.getCriadoEm(), p.getAtualizadoEm());
    }
}
