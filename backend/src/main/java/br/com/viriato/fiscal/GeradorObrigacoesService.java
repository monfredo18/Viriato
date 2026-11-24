package br.com.viriato.fiscal;

import br.com.viriato.cadastro.Cliente;
import br.com.viriato.cadastro.ClienteRepository;
import br.com.viriato.cadastro.Entrega;
import br.com.viriato.cadastro.EntregaRepository;
import br.com.viriato.comum.Datas;
import br.com.viriato.comum.NegocioException;
import br.com.viriato.crm.Enums.StatusContratual;
import br.com.viriato.notificacao.NotificacaoService;
import br.com.viriato.processo.DependenciaService;
import br.com.viriato.processo.Processo;
import br.com.viriato.processo.ProcessoRepository;
import br.com.viriato.processo.ProcessoService;
import br.com.viriato.usuario.Papel;
import br.com.viriato.usuario.Usuario;
import br.com.viriato.usuario.UsuarioRepository;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Abre, para cada empresa ativa e cada obrigação vinculada que vale na competência, o processo com o prazo calculado pelo
 * calendário fiscal (feriados nacionais, da UF e do município da empresa). Idempotente: empresa, obrigação e competência
 * nunca geram dois processos. Roda sozinho no dia 1º de cada mês (para a competência do mês anterior) e também sob demanda.
 */
@Service
public class GeradorObrigacoesService {
    private static final Logger log = LoggerFactory.getLogger(GeradorObrigacoesService.class);

    public record Resultado(int ano, int mes, int gerados, int jaExistiam, int dependenciasCriadas, List<String> ignorados) {}

    private final ObrigacaoModeloRepository modelos;
    private final ClienteObrigacaoRepository vinculos;
    private final ObrigacaoModeloDependenciaRepository modeloDeps;
    private final ClienteRepository clientes;
    private final EntregaRepository entregas;
    private final UsuarioRepository usuarios;
    private final ProcessoRepository processos;
    private final ProcessoService processoService;
    private final DependenciaService dependencias;
    private final CalendarioFiscalService calendarios;
    private final NotificacaoService notificacoes;
    private final br.com.viriato.cadastro.TenantIds tenants;

    public GeradorObrigacoesService(ObrigacaoModeloRepository modelos, ClienteObrigacaoRepository vinculos, ObrigacaoModeloDependenciaRepository modeloDeps,
                                    ClienteRepository clientes, EntregaRepository entregas, UsuarioRepository usuarios, ProcessoRepository processos,
                                    ProcessoService processoService, DependenciaService dependencias, CalendarioFiscalService calendarios,
                                    NotificacaoService notificacoes, br.com.viriato.cadastro.TenantIds tenants) {
        this.modelos = modelos;
        this.vinculos = vinculos;
        this.modeloDeps = modeloDeps;
        this.clientes = clientes;
        this.entregas = entregas;
        this.usuarios = usuarios;
        this.processos = processos;
        this.processoService = processoService;
        this.dependencias = dependencias;
        this.calendarios = calendarios;
        this.notificacoes = notificacoes;
        this.tenants = tenants;
    }

    /** Dia 1º de cada mês, de madrugada: gera a competência do mês anterior para todas as empresas do sistema. */
    @Scheduled(cron = "${viriato.obrigacoes.cron:0 0 5 1 * *}", zone = "America/Sao_Paulo")
    public void mensal() {
        YearMonth anterior = YearMonth.from(Datas.hoje()).minusMonths(1);
        for (Long tenantId : tenants.todos()) {
            try {
                Resultado r = gerar(tenantId, anterior.getYear(), anterior.getMonthValue());
                if (r.gerados() > 0) notificacoes.avisarGestao(tenantId, null, null, r.gerados() + " obrigações geradas para " + "%02d/%d".formatted(r.mes(), r.ano()),
                        r.jaExistiam() > 0 ? r.jaExistiam() + " já existiam" : "Competência " + "%02d/%d".formatted(r.mes(), r.ano()));
            } catch (Exception e) {
                log.error("Falha ao gerar as obrigações da empresa {}", tenantId, e);
            }
        }
    }

    /** Gera a competência pedida para uma empresa do sistema (botão "Gerar obrigações da competência"). */
    public Resultado gerar(Long tenantId, int ano, int mes) {
        if (mes < 1 || mes > 12 || ano < 2000 || ano > 2100) throw new NegocioException("Competência inválida");
        List<String> ignorados = new ArrayList<>();
        int gerados = 0, existiam = 0;
        Map<Long, ObrigacaoModelo> ms = modelos.findByTenantIdAndAtivoTrueOrderByNome(tenantId).stream()
                .filter(m -> MatrizService.aplicaNaCompetencia(m, mes)).collect(Collectors.toMap(ObrigacaoModelo::getId, Function.identity()));
        Map<Long, Entrega> es = entregas.findByTenantIdOrderByNome(tenantId).stream().collect(Collectors.toMap(Entrega::getId, Function.identity()));
        for (Cliente c : clientes.findByTenantIdOrderByNome(tenantId)) {
            if (!c.isAtivo() || c.getStatusContratual() == StatusContratual.SUSPENSO || c.getStatusContratual() == StatusContratual.ENCERRADO) continue;
            CalendarioFiscal cal = calendarios.calendario(tenantId, c.getUf(), c.getCodigoMunicipioIbge());
            for (ClienteObrigacao v : vinculos.findByClienteIdAndTenantId(c.getId(), tenantId)) {
                ObrigacaoModelo m = ms.get(v.getModeloId());
                if (m == null || !v.isAtivo()) continue;
                if (processos.existsByTenantIdAndClienteIdAndObrigacaoModeloIdAndCompetenciaAnoAndCompetenciaMes(tenantId, c.getId(), m.getId(), ano, mes)) { existiam++; continue; }
                Entrega e = es.get(m.getEntregaId());
                if (e == null) { ignorados.add(c.getNome() + " — " + m.getNome() + ": entrega removida"); continue; }
                try {
                    processoService.criarAutomatico(tenantId, c, e, responsavel(v, m, e, tenantId), MatrizService.vencimento(cal, m, ano, mes), ano, mes, m.getId(), m.getNome());
                    gerados++;
                } catch (DataIntegrityViolationException dup) {
                    if (processos.existsByTenantIdAndClienteIdAndObrigacaoModeloIdAndCompetenciaAnoAndCompetenciaMes(tenantId, c.getId(), m.getId(), ano, mes)) existiam++;   // outra execução criou no mesmo instante
                    else ignorados.add(c.getNome() + " — " + m.getNome() + ": não foi possível gravar o processo");
                } catch (NegocioException ex) {
                    ignorados.add(c.getNome() + " — " + m.getNome() + ": " + ex.getMessage());
                }
            }
        }
        return new Resultado(ano, mes, gerados, existiam, ligarDependencias(tenantId, ano, mes), ignorados);
    }

    /** Responsável: o definido para a empresa, senão o padrão da obrigação, senão o gestor do setor; sem ninguém, fica em branco. */
    private Long responsavel(ClienteObrigacao v, ObrigacaoModelo m, Entrega e, Long tenantId) {
        for (Long id : new Long[]{v.getResponsavelId(), m.getResponsavelPadraoId()}) {
            if (id != null && usuarios.findByIdAndTenantId(id, tenantId).filter(Usuario::isAtivo).isPresent()) return id;
        }
        return usuarios.findByTenantIdAndPapelAndSetorIdAndAtivoTrue(tenantId, Papel.GESTOR, e.getSetorDonoId()).stream().findFirst().map(Usuario::getId).orElse(null);
    }

    /** Liga, na competência, o processo de cada obrigação aos das obrigações de que ela depende (mesma empresa). */
    @Transactional
    public int ligarDependencias(Long tenantId, int ano, int mes) {
        int n = 0;
        var todas = modeloDeps.findByTenantId(tenantId);
        if (todas.isEmpty()) return 0;
        Map<String, Processo> porChave = new HashMap<>();
        for (Processo p : processos.findByTenantIdAndCompetenciaAnoAndCompetenciaMes(tenantId, ano, mes)) {
            if (p.getObrigacaoModeloId() != null && p.getClienteId() != null) porChave.put(p.getClienteId() + ":" + p.getObrigacaoModeloId(), p);
        }
        for (var d : todas) {
            for (Processo p : new ArrayList<>(porChave.values())) {
                if (!d.getModeloId().equals(p.getObrigacaoModeloId())) continue;
                Processo pred = porChave.get(p.getClienteId() + ":" + d.getPredecessorModeloId());
                if (pred != null && dependencias.vincularPorRegra(p, pred)) n++;
            }
        }
        return n;
    }
}
