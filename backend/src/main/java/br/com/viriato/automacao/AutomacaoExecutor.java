package br.com.viriato.automacao;

import br.com.viriato.cadastro.Cliente;
import br.com.viriato.cadastro.ClienteRepository;
import br.com.viriato.cadastro.Entrega;
import br.com.viriato.cadastro.EntregaRepository;
import br.com.viriato.cadastro.Setor;
import br.com.viriato.cadastro.SetorRepository;
import br.com.viriato.comum.Datas;
import br.com.viriato.documento.ArmazenamentoArquivos;
import br.com.viriato.documento.Documento;
import br.com.viriato.documento.DocumentoRepository;
import br.com.viriato.fluxo.Etapa;
import br.com.viriato.fluxo.EtapaRepository;
import br.com.viriato.processo.Processo;
import br.com.viriato.processo.ProcessoHistorico;
import br.com.viriato.processo.ProcessoHistoricoRepository;
import br.com.viriato.processo.ProcessoRepository;
import br.com.viriato.usuario.Usuario;
import br.com.viriato.usuario.UsuarioRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Executa as regras de automação depois que o movimento do processo foi confirmado no banco. Cada regra é
 * independente: a falha de uma (ou de todas) NUNCA desfaz nem trava o movimento do processo, só fica registrada.
 * Ordem: gerar documento, depois webhook.
 */
@Component
public class AutomacaoExecutor {
    private static final Logger log = LoggerFactory.getLogger(AutomacaoExecutor.class);
    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATA_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final AutomacaoRegraRepository regras;
    private final AutomacaoExecucaoRepository execucoes;
    private final ProcessoRepository processos;
    private final ProcessoHistoricoRepository historicos;
    private final ClienteRepository clientes;
    private final EntregaRepository entregas;
    private final SetorRepository setores;
    private final UsuarioRepository usuarios;
    private final EtapaRepository etapas;
    private final DocumentoRepository documentos;
    private final ArmazenamentoArquivos storage;
    private final WebhookClient webhook;
    private final ObjectMapper json;
    private final PlatformTransactionManager tm;
    private final Executor executor;

    public AutomacaoExecutor(AutomacaoRegraRepository regras, AutomacaoExecucaoRepository execucoes, ProcessoRepository processos,
                             ProcessoHistoricoRepository historicos, ClienteRepository clientes, EntregaRepository entregas,
                             SetorRepository setores, UsuarioRepository usuarios, EtapaRepository etapas, DocumentoRepository documentos,
                             ArmazenamentoArquivos storage, WebhookClient webhook, ObjectMapper json,
                             PlatformTransactionManager tm, @Qualifier("automacaoTaskExecutor") Executor executor) {
        this.regras = regras;
        this.execucoes = execucoes;
        this.processos = processos;
        this.historicos = historicos;
        this.clientes = clientes;
        this.entregas = entregas;
        this.setores = setores;
        this.usuarios = usuarios;
        this.etapas = etapas;
        this.documentos = documentos;
        this.storage = storage;
        this.webhook = webhook;
        this.json = json;
        this.tm = tm;
        this.executor = executor;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void aoEvento(AutomacaoEvento e) {
        executor.execute(() -> {
            try {
                executar(e);
            } catch (Exception ex) {
                log.error("Falha inesperada nas automações do processo {}", e.processoId(), ex);
            }
        });
    }

    /** Tudo o que as regras precisam, lido de uma vez para não segurar transação durante o webhook. */
    private record Contexto(Long processoId, Long tenantId, String numero, Long clienteId, String etapaNome,
                            Map<String, String> variaveis, List<AutomacaoRegra> regras, List<FichaProcessoPdf.Linha> historico) {}

    public void executar(AutomacaoEvento e) {
        TransactionTemplate tx = new TransactionTemplate(tm);
        tx.setReadOnly(true);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        Contexto c = tx.execute(s -> carregar(e));
        if (c == null) return;

        for (AutomacaoRegra r : c.regras()) {
            StatusExecucao status;
            String msg;
            try {
                switch (r.getTipo()) {
                    case GERAR_DOCUMENTO -> {
                        String nomeFicha = nomeFicha(c);
                        byte[] ficha = FichaProcessoPdf.gerar(c.variaveis(), DATA_HORA.format(Instant.now().atZone(Datas.ZONA)), c.historico());
                        guardar(c, nomeFicha, ficha);
                        status = StatusExecucao.OK;
                        msg = "Documento \"" + nomeFicha + "\" anexado ao processo";
                    }
                    case WEBHOOK -> {
                        int codigo = webhook.enviar(r.getUrl(), r.getSegredo(), nomeEvento(e.evento()), corpoWebhook(c, e));
                        boolean ok = codigo >= 200 && codigo < 300;
                        status = ok ? StatusExecucao.OK : StatusExecucao.FALHA;
                        msg = "Resposta HTTP " + codigo;
                    }
                    default -> throw new IllegalStateException("Tipo desconhecido");
                }
            } catch (Exception ex) {
                status = StatusExecucao.FALHA;
                msg = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                log.warn("Automação {} do processo {} falhou: {}", r.getId(), c.processoId(), msg);
            }
            registrar(c, r, e.evento(), status, msg);
        }
    }

    private Contexto carregar(AutomacaoEvento e) {
        Processo p = processos.findById(e.processoId()).filter(x -> x.getTenantId().equals(e.tenantId())).orElse(null);
        if (p == null || p.getFluxoId() == null) return null;
        List<AutomacaoRegra> rs = regras.ativas(e.tenantId(), p.getFluxoId(), e.evento(), e.evento() == EventoAutomacao.ENTRAR_ETAPA ? e.etapaId() : null);
        if (rs.isEmpty()) return null;
        rs = rs.stream().sorted(Comparator.comparingInt((AutomacaoRegra r) -> r.getTipo().ordinal()).thenComparing(AutomacaoRegra::getId)).toList();

        Long tid = e.tenantId();
        Cliente cli = p.getClienteId() == null ? null : clientes.findByIdAndTenantId(p.getClienteId(), tid).orElse(null);
        String entrega = p.getEntregaId() == null ? null : entregas.findByIdAndTenantId(p.getEntregaId(), tid).map(Entrega::getNome).orElse(null);
        String setor = p.getSetorId() == null ? null : setores.findByIdAndTenantId(p.getSetorId(), tid).map(Setor::getNome).orElse(null);
        String resp = p.getResponsavelId() == null ? p.getResponsavelRemovidoNome() : usuarios.findByIdAndTenantId(p.getResponsavelId(), tid).map(Usuario::getNome).orElse(null);
        String etapa = e.etapaId() == null ? null : etapas.findById(e.etapaId()).map(Etapa::getNome).orElse(null);

        Map<String, String> v = new LinkedHashMap<>();
        v.put("empresa", cli == null ? "—" : cli.getNome());
        v.put("numero", p.getNumero());
        v.put("titulo", p.getTitulo());
        v.put("entrega", entrega == null ? "—" : entrega);
        v.put("setor", setor == null ? "—" : setor);
        v.put("responsavel", resp == null ? "—" : resp);
        v.put("etapa", etapa == null ? "—" : etapa);
        v.put("prazo", p.getPrazoEtapa() == null ? "sem prazo" : DATA.format(p.getPrazoEtapa()));
        v.put("situacao", switch (p.getStatus()) { case ABERTO -> "Em andamento"; case CONCLUIDO -> "Concluído"; case CANCELADO -> "Cancelado"; });

        List<FichaProcessoPdf.Linha> linhas = List.of();
        if (rs.stream().anyMatch(r -> r.getTipo() == TipoAutomacao.GERAR_DOCUMENTO)) {
            List<ProcessoHistorico> hs = new ArrayList<>(historicos.findByProcessoIdAndTenantIdOrderByIdDesc(p.getId(), tid));
            if (hs.size() > 100) hs = hs.subList(0, 100);
            Collections.reverse(hs);
            Map<Long, String> nomes = usuarios.findByTenantIdOrderByNome(tid).stream().collect(Collectors.toMap(Usuario::getId, Usuario::getNome));
            linhas = hs.stream().map(h -> new FichaProcessoPdf.Linha(DATA_HORA.format(h.getCriadoEm().atZone(Datas.ZONA)), h.getAcao(),
                    ((h.getDeEtapa() != null || h.getParaEtapa() != null ? (h.getDeEtapa() == null ? "" : h.getDeEtapa() + " → ") + h.getParaEtapa() + " " : "")
                            + (h.getComentario() == null ? "" : h.getComentario())).trim(),
                    h.getUsuarioId() == null ? null : nomes.getOrDefault(h.getUsuarioId(), "usuário removido"))).toList();
        }
        return new Contexto(p.getId(), tid, p.getNumero(), cli == null ? null : cli.getId(),
                etapa, v, rs, linhas);
    }

    private static String nomeEvento(EventoAutomacao e) { return e == EventoAutomacao.CANCELADO ? "CANCELADO" : "ENTROU_NA_ETAPA"; }

    private static String nomeFicha(Contexto c) {
        String etapa = c.etapaNome() == null ? "" : "-" + c.etapaNome();
        String base = ("Ficha-" + c.numero() + etapa).replaceAll("[^\\p{L}\\p{N}._-]+", "_");
        return base + ".pdf";
    }

    private void guardar(Contexto c, String nome, byte[] pdf) {
        String chave = "tenant-%d/processo-%d/%s-%s".formatted(c.tenantId(), c.processoId(), UUID.randomUUID(), nome);
        storage.salvar(chave, new ByteArrayInputStream(pdf), pdf.length, "application/pdf");
        Documento d = new Documento();
        d.setTenantId(c.tenantId());
        d.setProcessoId(c.processoId());
        d.setNomeOriginal(nome);
        d.setChaveS3(chave);
        d.setContentType("application/pdf");
        d.setTamanho(pdf.length);
        d.setEnviadoPor(null);
        d.setAutomatico(true);
        try {
            novaTransacao(() -> documentos.save(d));
        } catch (RuntimeException ex) {
            storage.remover(chave);   // não deixa arquivo órfão
            throw ex;
        }
    }

    private String corpoWebhook(Contexto c, AutomacaoEvento e) throws Exception {
        Map<String, Object> processo = new LinkedHashMap<>();
        processo.put("id", c.processoId());
        processo.put("numero", c.numero());
        processo.put("titulo", c.variaveis().get("titulo"));
        processo.put("situacao", c.variaveis().get("situacao"));
        processo.put("empresaId", c.clienteId());
        processo.put("empresa", c.variaveis().get("empresa"));
        processo.put("entrega", c.variaveis().get("entrega"));
        processo.put("setor", c.variaveis().get("setor"));
        processo.put("responsavel", c.variaveis().get("responsavel"));
        processo.put("etapa", c.variaveis().get("etapa"));
        processo.put("prazoEtapa", c.variaveis().get("prazo"));
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("evento", nomeEvento(e.evento()));
        corpo.put("disparadoEm", Instant.now().toString());
        corpo.put("processo", processo);
        return json.writeValueAsString(corpo);
    }

    private void registrar(Contexto c, AutomacaoRegra r, EventoAutomacao evento, StatusExecucao status, String mensagem) {
        AutomacaoExecucao x = new AutomacaoExecucao();
        x.setTenantId(c.tenantId());
        x.setProcessoId(c.processoId());
        x.setRegraId(r.getId());
        x.setTipo(r.getTipo());
        x.setEvento(evento);
        x.setEtapaNome(c.etapaNome());
        x.setStatus(status);
        x.setMensagem(mensagem == null ? null : mensagem.length() > 500 ? mensagem.substring(0, 500) : mensagem);
        novaTransacao(() -> execucoes.save(x));
    }

    /**
     * As regras rodam logo depois do commit do movimento do processo; sem uma transação própria, o que elas gravam
     * pegaria carona na transação que já terminou e se perderia.
     */
    private void novaTransacao(Runnable acao) {
        TransactionTemplate tx = new TransactionTemplate(tm);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(st -> acao.run());
    }
}
