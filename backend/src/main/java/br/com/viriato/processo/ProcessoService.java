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
import java.time.Instant;
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
    private final RequisitoService requisitos;
    private final ProcessoRequisitoRepository confirmacoes;

    public ProcessoService(ProcessoRepository processos, ProcessoHistoricoRepository historicos,
                           FluxoRepository fluxos, EtapaRepository etapas, TransicaoRepository transicoes,
                           UsuarioRepository usuarios, NotificacaoService notificacoes, CadastroService cadastros,
                           ClienteRepository clientes, EntregaRepository entregasRepo, SetorRepository setoresRepo,
                           ObjectMapper json, RequisitoService requisitos, ProcessoRequisitoRepository confirmacoes) {
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
        this.requisitos = requisitos;
        this.confirmacoes = confirmacoes;
    }

    public record Filtro(StatusProcesso status, Long etapaId, Long responsavelId, Long clienteId, Long setorId,
                         Long entregaId, String busca, boolean atrasados) {}

    /**
     * Dados do formulário de novo processo. Quem decide setor e responsável é o papel de quem cria:
     * administrador informa os dois; gestor informa o responsável (o setor é o dele); usuário informa
     * só empresa e entrega (o setor é o dele e o responsável é ele mesmo).
     */
    public record NovoProcesso(Long clienteId, Long entregaId, Long setorId, Long responsavelId,
                               String descricao, Map<String, Object> camposExtras) {}

    // ---------- criação ----------

    @Transactional
    public ProcessoDto criar(UsuarioLogado quem, NovoProcesso n) {
        Long tid = quem.tenantId();
        Cliente cliente = cadastros.clienteAtivo(tid, n.clienteId());
        Long setorEscolhido = quem.papel() == Papel.ADMIN ? n.setorId() : setorDe(quem);
        if (setorEscolhido == null) {
            throw new NegocioException(quem.papel() == Papel.ADMIN ? "Informe o setor"
                    : "Seu usuário não tem setor definido. Peça ao administrador para definir o seu setor.");
        }
        Setor setor = cadastros.setorAtivo(tid, setorEscolhido);
        Long responsavelId = quem.papel() == Papel.USUARIO ? quem.id() : n.responsavelId();
        if (responsavelId == null) throw new NegocioException("Informe o responsável");
        Entrega entrega = cadastros.entregaDoSetor(tid, setor, n.entregaId());
        Fluxo fluxo = fluxos.findByIdAndTenantId(entrega.getFluxoId(), tid)
                .orElseThrow(() -> new NegocioException("A entrega não tem um fluxo válido"));
        if (!fluxo.isAtivo()) throw new NegocioException("O fluxo desta entrega está inativo");
        Etapa inicial = etapas.findFirstByFluxoIdAndTenantIdAndInicialTrue(fluxo.getId(), tid)
                .orElseThrow(() -> new NegocioException("Fluxo sem etapa inicial"));
        exigirResponsavelDoSetor(responsavelId, setor.getId(), tid);
        Long fluxoId = fluxo.getId();
        String titulo = entrega.getNome() + " — " + cliente.getNome();
        if (titulo.length() > 200) titulo = titulo.substring(0, 200);
        String descricao = n.descricao();
        Map<String, Object> extras = n.camposExtras();

        Processo p = new Processo();
        p.setTenantId(tid);
        p.setNumero(cliente.getNumero());     // o número do processo é o número da empresa
        p.setTitulo(titulo);
        p.setDescricao(descricao);
        p.setClienteId(cliente.getId());
        p.setEntregaId(entrega.getId());
        p.setSetorId(setor.getId());
        p.setFluxoId(fluxoId);
        p.setEtapaAtualId(inicial.getId());
        p.setResponsavelId(responsavelId);
        p.setPrazoEtapa(calcularPrazo(inicial));
        p.setEtapaDesde(Instant.now());
        p.setCamposExtras(serializar(extras));
        p.setCriadoPor(quem.id());
        p = processos.save(p);

        registrar(p, quem.id(), "CRIADO", null, inicial.getNome(), null);
        notificacoes.notificar(p, "Novo processo aberto", null, quem.id());
        return paraDto(p);
    }

    // ---------- consulta ----------

    @Transactional(readOnly = true)
    public Page<ProcessoDto> listar(UsuarioLogado quem, Filtro f, Pageable page) {
        Long tenantId = quem.tenantId();
        boolean restrito = quem.papel() != Papel.ADMIN;
        Long meuSetor = restrito ? setorDe(quem) : null;
        Specification<Processo> spec = (root, q, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.equal(root.get("tenantId"), tenantId));
            // Só o administrador enxerga processos de todos os setores.
            if (restrito) ps.add(meuSetor == null ? cb.disjunction() : cb.equal(root.get("setorId"), meuSetor));
            // Processos de funcionário apagado: só gestor do setor e administrador.
            if (quem.papel() == Papel.USUARIO) ps.add(cb.isNull(root.get("responsavelRemovidoNome")));
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
    public Detalhe detalhe(UsuarioLogado quem, Long id) {
        Long tenantId = quem.tenantId();
        Processo p = buscar(quem, id);
        List<EtapaDto> proximas = proximasEtapas(p);
        Map<Long, String> nomes = usuarios.findByTenantIdOrderByNome(tenantId).stream()
                .collect(Collectors.toMap(Usuario::getId, Usuario::getNome));
        List<HistoricoDto> hist = historicos.findByProcessoIdAndTenantIdOrderByIdDesc(id, tenantId).stream()
                .map(h -> new HistoricoDto(h.getId(), h.getAcao(), nomes.get(h.getUsuarioId()), h.getDeEtapa(),
                        h.getParaEtapa(), h.getComentario(), h.getCriadoEm()))
                .toList();
        Integer ordem = p.getEtapaAtualId() == null ? null : etapas.findById(p.getEtapaAtualId()).map(Etapa::getOrdem).orElse(null);
        return new Detalhe(paraDto(p), proximas, hist, p.getStatus() == StatusProcesso.ABERTO ? requisitos.situacao(p) : List.of(), ordem);
    }

    // ---------- operações ----------

    @Transactional
    public ProcessoDto avancar(UsuarioLogado quem, Long id, Long etapaDestinoId, String comentario) {
        Processo p = buscar(quem, id);
        exigirAberto(p);
        exigirPodeMexer(quem, p);
        Etapa destino = etapas.findByIdAndTenantId(etapaDestinoId, quem.tenantId())
                .orElseThrow(() -> new NaoEncontradoException("Etapa"));
        if (!transicoes.existsByDeEtapaAndParaEtapaAndTenantId(p.getEtapaAtualId(), destino.getId(), quem.tenantId())) {
            throw new NegocioException("Transição não permitida pelo fluxo");
        }
        Etapa origem = etapas.findById(p.getEtapaAtualId()).orElseThrow();
        // Avançar exige os requisitos da etapa de origem; voltar para uma etapa anterior não exige nada.
        if (destino.getOrdem() > origem.getOrdem()) requisitos.exigirCumpridos(p, origem);

        p.setEtapaAtualId(destino.getId());
        p.setEtapaDesde(Instant.now());
        p.setPrazoAlertado(false);
        if (destino.isFinalEtapa()) {
            p.setStatus(StatusProcesso.CONCLUIDO);
            p.setPrazoEtapa(null);
        } else {
            p.setPrazoEtapa(calcularPrazo(destino));
        }
        p = processos.save(p);
        registrar(p, quem.id(), "MOVIDO", origem.getNome(), destino.getNome(), comentario);

        String detalhe = origem.getNome() + " → " + destino.getNome() + (comentario == null || comentario.isBlank() ? "" : ". " + comentario);
        notificacoes.notificar(p, destino.isFinalEtapa() ? "Processo concluído" : "Processo movido para " + destino.getNome(), detalhe, quem.id());
        return paraDto(p);
    }

    /** Marca ou desmarca a confirmação de um requisito do tipo CONFIRMACAO da etapa atual. */
    @Transactional
    public void definirRequisito(UsuarioLogado quem, Long id, Long requisitoId, boolean cumprido) {
        Processo p = buscar(quem, id);
        exigirAberto(p);
        exigirPodeMexer(quem, p);
        EtapaRequisito r = requisitos.daEtapaAtual(p, requisitoId, TipoRequisito.CONFIRMACAO);
        var existente = confirmacoes.findByProcessoIdAndRequisitoId(p.getId(), r.getId());
        if (cumprido) {
            ProcessoRequisito c = existente.orElseGet(ProcessoRequisito::new);
            c.setTenantId(p.getTenantId());
            c.setProcessoId(p.getId());
            c.setRequisitoId(r.getId());
            c.setCumpridoPor(quem.id());
            c.setCumpridoEm(Instant.now());
            confirmacoes.save(c);
        } else {
            existente.ifPresent(confirmacoes::delete);
        }
        registrar(p, quem.id(), "REQUISITO", null, null, (cumprido ? "Confirmado: " : "Desmarcado: ") + r.getDescricao());
    }

    @Transactional
    public void comentar(UsuarioLogado quem, Long id, String texto) {
        Processo p = buscar(quem, id);
        exigirPodeMexer(quem, p);
        registrar(p, quem.id(), "COMENTARIO", null, null, texto);
        notificacoes.notificar(p, "Novo comentário", texto, quem.id());
    }

    @Transactional
    public ProcessoDto atribuir(UsuarioLogado quem, Long id, Long responsavelId) {
        Processo p = buscar(quem, id);
        exigirAberto(p);
        boolean gestor = quem.papel() != Papel.USUARIO;
        if (!gestor && !quem.id().equals(p.getResponsavelId())) {
            throw new NegocioException("Apenas o responsável atual ou um gestor pode reatribuir");
        }
        if (responsavelId != null) exigirResponsavelDoSetor(responsavelId, p.getSetorId(), quem.tenantId());
        p.setResponsavelId(responsavelId);
        p.setPrazoAlertado(false);
        p = processos.save(p);
        String nome = responsavelId == null ? "ninguém" : usuarios.findById(responsavelId).map(Usuario::getNome).orElse("?");
        registrar(p, quem.id(), "ATRIBUIDO", null, null, "Responsável: " + nome);
        notificacoes.notificar(p, "Processo atribuído a " + nome, null, quem.id());
        return paraDto(p);
    }

    @Transactional
    public ProcessoDto cancelar(UsuarioLogado quem, Long id, String motivo) {
        Processo p = buscar(quem, id);   // antes da checagem de papel: processo de outro setor é sempre "não encontrado"
        if (quem.papel() == Papel.USUARIO) throw new NegocioException("Apenas gestores podem cancelar processos");
        exigirAberto(p);
        p.setStatus(StatusProcesso.CANCELADO);
        p.setPrazoEtapa(null);
        p = processos.save(p);
        registrar(p, quem.id(), "CANCELADO", null, null, motivo);
        notificacoes.notificar(p, "Processo cancelado", motivo, quem.id());
        return paraDto(p);
    }

    /** Processo de outro setor responde "não encontrado" a quem não é administrador. */
    public Processo buscar(UsuarioLogado quem, Long id) {
        Processo p = processos.findByIdAndTenantId(id, quem.tenantId()).orElseThrow(() -> new NaoEncontradoException("Processo"));
        if (quem.papel() != Papel.ADMIN) {
            Long meuSetor = setorDe(quem);
            if (meuSetor == null || !meuSetor.equals(p.getSetorId())) throw new NaoEncontradoException("Processo");
            if (quem.papel() == Papel.USUARIO && p.getResponsavelRemovidoNome() != null) throw new NaoEncontradoException("Processo");
        }
        return p;
    }

    /** Setor atual de quem está logado (lido do banco, não do token, para refletir mudanças). */
    private Long setorDe(UsuarioLogado quem) {
        return usuarios.findByIdAndTenantId(quem.id(), quem.tenantId()).map(Usuario::getSetorId).orElse(null);
    }

    /**
     * Passa os processos EM ANDAMENTO (inclusive os atrasados) de um funcionário que será apagado para os
     * herdeiros escolhidos, divididos igualmente, todos com o mesmo novo prazo. Cada herdeiro só recebe
     * processos do próprio setor (o administrador pode herdar de qualquer setor). Devolve quantos passaram.
     */
    @Transactional
    public int transferirEmAndamento(UsuarioLogado quem, Usuario removido, List<Long> herdeiroIds, LocalDate prazo) {
        Long tid = quem.tenantId();
        List<Processo> abertos = processos.findByTenantIdAndResponsavelIdAndStatusOrderById(tid, removido.getId(), StatusProcesso.ABERTO);
        if (abertos.isEmpty()) return 0;
        if (herdeiroIds == null || herdeiroIds.isEmpty()) throw new NegocioException("Escolha ao menos um funcionário para herdar os processos");
        if (prazo == null) throw new NegocioException("Informe o novo prazo dos processos");
        if (prazo.isBefore(Datas.hoje())) throw new NegocioException("O novo prazo não pode ser anterior a hoje");
        List<Usuario> herdeiros = herdeiroIds.stream().distinct()
                .map(id -> usuarios.findByIdAndTenantId(id, tid).filter(Usuario::isAtivo)
                        .filter(u -> !u.getId().equals(removido.getId()))
                        .orElseThrow(() -> new NegocioException("Um dos funcionários escolhidos não pode herdar os processos")))
                .toList();
        Map<Long, Integer> giro = new HashMap<>();   // revezamento por setor
        String quando = prazo.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"));
        for (Processo p : abertos) {
            List<Usuario> elegiveis = herdeiros.stream()
                    .filter(h -> h.getPapel() == Papel.ADMIN || Objects.equals(h.getSetorId(), p.getSetorId())).toList();
            if (elegiveis.isEmpty()) {
                throw new NegocioException("Nenhum dos funcionários escolhidos é do setor do processo " + p.getNumero() + " (" + p.getTitulo() + ")");
            }
            int vez = giro.getOrDefault(p.getSetorId(), 0);
            Usuario herdeiro = elegiveis.get(vez % elegiveis.size());
            giro.put(p.getSetorId(), vez + 1);
            p.setResponsavelId(herdeiro.getId());
            p.setPrazoEtapa(prazo);
            p.setPrazoAlertado(false);
            processos.save(p);
            registrar(p, quem.id(), "ATRIBUIDO", null, null, "Responsável: " + herdeiro.getNome() + " (herdou de "
                    + removido.getNome() + ", funcionário removido). Novo prazo: " + quando);
        }
        return abertos.size();
    }

    // ---------- auxiliares ----------

    private void exigirAberto(Processo p) {
        if (p.getStatus() != StatusProcesso.ABERTO) throw new NegocioException("Processo não está aberto");
    }

    /**
     * Administrador e gestor mexem em qualquer processo que enxergam (o gestor, só do próprio setor).
     * O usuário comum enxerga os do seu setor, mas só mexe naqueles em que é o responsável.
     */
    public void exigirPodeMexer(UsuarioLogado quem, Processo p) {
        if (quem.papel() != Papel.USUARIO) return;
        if (!quem.id().equals(p.getResponsavelId())) {
            throw new NegocioException("Você só pode mexer nos processos em que é o responsável");
        }
    }

    /** O responsável precisa estar ativo e ser do setor do processo (o administrador pode ser responsável em qualquer um). */
    private void exigirResponsavelDoSetor(Long usuarioId, Long setorId, Long tenantId) {
        Usuario u = usuarios.findByIdAndTenantId(usuarioId, tenantId).filter(Usuario::isAtivo)
                .orElseThrow(() -> new NegocioException("Responsável inválido"));
        if (u.getPapel() != Papel.ADMIN && !Objects.equals(u.getSetorId(), setorId)) {
            throw new NegocioException("O responsável precisa ser do setor do processo");
        }
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
                .map(e -> EtapaDto.de(e, null))
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
        Set<Long> fluxoIds = ps.stream().map(Processo::getFluxoId).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> etapaIds = ps.stream().map(Processo::getEtapaAtualId).filter(Objects::nonNull).collect(Collectors.toSet());
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

    /** Nome do responsável; se ele foi apagado, o nome guardado na época, marcado como removido. */
    private static String nomeResponsavel(Processo p, Lookups lk) {
        if (p.getResponsavelId() != null) return lk.usuarios().get(p.getResponsavelId());
        return p.getResponsavelRemovidoNome() == null ? null : p.getResponsavelRemovidoNome() + " (removido)";
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
                p.getResponsavelId(), nomeResponsavel(p, lk),
                p.getStatus(), p.getPrazoEtapa(), atrasado, ler(p.getCamposExtras()),
                p.getCriadoEm(), p.getAtualizadoEm());
    }
}
