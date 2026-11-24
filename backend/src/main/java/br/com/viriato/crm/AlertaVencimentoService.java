package br.com.viriato.crm;

import br.com.viriato.cadastro.Cliente;
import br.com.viriato.cadastro.ClienteRepository;
import br.com.viriato.comum.Datas;
import br.com.viriato.crm.Enums.StatusContratual;
import br.com.viriato.crm.Enums.TipoChecklist;
import br.com.viriato.notificacao.NotificacaoService;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Todo dia confere certificados digitais e procurações: avisa gestores e administradores quando faltam 30, 15 ou 5 dias e
 * quando vence (um aviso por item e por marco). Também monta o painel de "Saúde cadastral".
 */
@Service
public class AlertaVencimentoService {
    private static final Logger log = LoggerFactory.getLogger(AlertaVencimentoService.class);
    static final String CERTIFICADO = "CERTIFICADO", PROCURACAO = "PROCURACAO";

    public record Item(String tipo, Long itemId, Long empresaId, String empresaNome, String descricao, LocalDate vencimento, long dias, String gravidade) {}
    public record Resumo(long certificadosVencidos, long certificadosAVencer, long procuracoesVencidas, long procuracoesAVencer,
                         long empresasSemCnpj, long empresasSemRegime, long empresasSemCertificado, long onboardingEmAndamento, long offboardingEmAndamento) {}
    public record Saude(Resumo resumo, List<Item> itens) {}

    private final ClienteCertificadoRepository certs;
    private final ClienteProcuracaoRepository procs;
    private final ClienteRepository clientes;
    private final AlertaVencimentoRepository alertas;
    private final NotificacaoService notificacoes;
    private final ClienteChecklistItemRepository checklist;

    public AlertaVencimentoService(ClienteCertificadoRepository certs, ClienteProcuracaoRepository procs, ClienteRepository clientes,
                                   AlertaVencimentoRepository alertas, NotificacaoService notificacoes, ClienteChecklistItemRepository checklist) {
        this.certs = certs;
        this.procs = procs;
        this.clientes = clientes;
        this.alertas = alertas;
        this.notificacoes = notificacoes;
        this.checklist = checklist;
    }

    /** O marco que vale hoje: 0 = vencido, 5, 15 ou 30 dias; nulo se ainda falta mais de 30. */
    static Integer marco(long dias) {
        if (dias < 0) return 0;
        if (dias <= 5) return 5;
        if (dias <= 15) return 15;
        if (dias <= 30) return 30;
        return null;
    }

    @Scheduled(cron = "${viriato.alertas.cron:0 30 7 * * *}", zone = "America/Sao_Paulo")
    @Transactional
    public int verificar() {
        LocalDate hoje = Datas.hoje();
        int enviados = 0;
        Map<Long, Cliente> porId = clientes.findAll().stream().collect(Collectors.toMap(Cliente::getId, Function.identity()));
        for (ClienteCertificado c : certs.findByAtivoTrue()) {
            if (c.getValidoAte() == null) continue;
            enviados += avisar(CERTIFICADO, c.getId(), c.getTenantId(), porId.get(c.getClienteId()), "Certificado digital " + c.getTipo(), c.getValidoAte(), hoje);
        }
        for (ClienteProcuracao p : procs.findByAtivoTrue()) {
            if (p.getDataVencimento() == null) continue;
            enviados += avisar(PROCURACAO, p.getId(), p.getTenantId(), porId.get(p.getClienteId()), "Procuração " + rotulo(p.getTipo()), p.getDataVencimento(), hoje);
        }
        if (enviados > 0) log.info("Alertas de vencimento enviados: {}", enviados);
        return enviados;
    }

    private int avisar(String tipo, Long ref, Long tenantId, Cliente cli, String o, LocalDate vence, LocalDate hoje) {
        if (cli == null || cli.getStatusContratual() == StatusContratual.ENCERRADO) return 0;
        long dias = ChronoUnit.DAYS.between(hoje, vence);
        Integer marco = marco(dias);
        if (marco == null || alertas.existsByTipoAndRefIdAndMarco(tipo, ref, marco)) return 0;
        AlertaVencimento a = new AlertaVencimento();
        a.setTenantId(tenantId);
        a.setTipo(tipo);
        a.setRefId(ref);
        a.setMarco(marco);
        alertas.save(a);
        String titulo = dias < 0 ? o + " vencido" : dias == 0 ? o + " vence hoje" : o + " vence em " + dias + (dias == 1 ? " dia" : " dias");
        notificacoes.avisarGestao(tenantId, cli.getId(), cli.getNome(), titulo, "Validade: " + Datas.DATA.format(vence));
        return 1;
    }

    @Transactional(readOnly = true)
    public Saude saude(Long tenantId) {
        LocalDate hoje = Datas.hoje();
        List<Cliente> todos = clientes.findByTenantIdOrderByNome(tenantId);
        Map<Long, Cliente> porId = todos.stream().collect(Collectors.toMap(Cliente::getId, Function.identity()));
        List<Item> itens = new ArrayList<>();
        long certVenc = 0, certAvencer = 0, procVenc = 0, procAvencer = 0;
        List<ClienteCertificado> cs = certs.findByTenantIdAndAtivoTrue(tenantId);
        for (ClienteCertificado c : cs) {
            Cliente cli = porId.get(c.getClienteId());
            if (c.getValidoAte() == null || cli == null || cli.getStatusContratual() == StatusContratual.ENCERRADO) continue;
            long dias = ChronoUnit.DAYS.between(hoje, c.getValidoAte());
            if (dias > 30) continue;
            if (dias < 0) certVenc++; else certAvencer++;
            itens.add(new Item(CERTIFICADO, c.getId(), cli.getId(), cli.getNome(), "Certificado digital " + c.getTipo(), c.getValidoAte(), dias, dias <= 5 ? "CRITICA" : "ALERTA"));
        }
        for (ClienteProcuracao p : procs.findByTenantIdAndAtivoTrue(tenantId)) {
            Cliente cli = porId.get(p.getClienteId());
            if (p.getDataVencimento() == null || cli == null || cli.getStatusContratual() == StatusContratual.ENCERRADO) continue;
            long dias = ChronoUnit.DAYS.between(hoje, p.getDataVencimento());
            if (dias > 30) continue;
            if (dias < 0) procVenc++; else procAvencer++;
            itens.add(new Item(PROCURACAO, p.getId(), cli.getId(), cli.getNome(), "Procuração " + rotulo(p.getTipo()), p.getDataVencimento(), dias, dias <= 5 ? "CRITICA" : "ALERTA"));
        }
        itens.sort(Comparator.comparingLong(Item::dias));

        var comCertificadoValido = cs.stream().filter(c -> c.getValidoAte() == null || !c.getValidoAte().isBefore(hoje)).map(ClienteCertificado::getClienteId).collect(Collectors.toSet());
        List<Cliente> emOperacao = todos.stream().filter(c -> c.isAtivo() && (c.getStatusContratual() == StatusContratual.ATIVO || c.getStatusContratual() == StatusContratual.ONBOARDING)).toList();
        long semCnpj = emOperacao.stream().filter(c -> c.getCnpj() == null).count();
        long semRegime = emOperacao.stream().filter(c -> c.getRegimeTributario() == null).count();
        long semCert = emOperacao.stream().filter(c -> !comCertificadoValido.contains(c.getId())).count();
        long onb = todos.stream().filter(c -> c.getStatusContratual() == StatusContratual.ONBOARDING).count();
        long off = todos.stream().filter(c -> c.getStatusContratual() == StatusContratual.OFFBOARDING).count();
        return new Saude(new Resumo(certVenc, certAvencer, procVenc, procAvencer, semCnpj, semRegime, semCert, onb, off), itens.size() > 50 ? itens.subList(0, 50) : itens);
    }

    private static String rotulo(Enums.TipoProcuracao t) {
        return switch (t) { case ECAC -> "e-CAC"; case SEFAZ -> "SEFAZ"; case PREFEITURA -> "da Prefeitura"; case FGTS_CONECTIVIDADE -> "FGTS (Conectividade)"; case OUTRO -> "(outra)"; };
    }
}
