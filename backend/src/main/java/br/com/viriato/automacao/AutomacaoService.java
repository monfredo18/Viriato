package br.com.viriato.automacao;

import br.com.viriato.comum.NaoEncontradoException;
import br.com.viriato.comum.NegocioException;
import br.com.viriato.fluxo.Etapa;
import br.com.viriato.fluxo.EtapaRepository;
import br.com.viriato.fluxo.FluxoRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Configuração das regras de automação de um fluxo (administrador e gestor). */
@Service
public class AutomacaoService {
    static final int MAXIMO_POR_ETAPA = 10;

    /** O segredo nunca volta pela API: só se informa se existe. */
    public record RegraDto(Long id, Long fluxoId, Long etapaId, String etapaNome, EventoAutomacao evento, TipoAutomacao tipo, boolean ativo,
                           String url, boolean temSegredo) {}

    public record RegraReq(EventoAutomacao evento, Long etapaId, TipoAutomacao tipo, String url, String segredo, Boolean ativo) {}

    public record ExecucaoDto(Long id, TipoAutomacao tipo, EventoAutomacao evento, String etapa, StatusExecucao status, String mensagem, Instant executadoEm) {}

    private final AutomacaoRegraRepository repo;
    private final AutomacaoExecucaoRepository execucoes;
    private final FluxoRepository fluxos;
    private final EtapaRepository etapas;
    private final boolean permitirRedeInterna;

    public AutomacaoService(AutomacaoRegraRepository repo, AutomacaoExecucaoRepository execucoes, FluxoRepository fluxos, EtapaRepository etapas,
                            @Value("${viriato.automacao.permitir-rede-interna:false}") boolean permitirRedeInterna) {
        this.repo = repo;
        this.execucoes = execucoes;
        this.fluxos = fluxos;
        this.etapas = etapas;
        this.permitirRedeInterna = permitirRedeInterna;
    }

    private void exigirFluxo(Long tenantId, Long fluxoId) {
        fluxos.findByIdAndTenantId(fluxoId, tenantId).orElseThrow(() -> new NaoEncontradoException("Fluxo"));
    }

    @Transactional(readOnly = true)
    public List<RegraDto> listar(Long tenantId, Long fluxoId) {
        exigirFluxo(tenantId, fluxoId);
        Map<Long, String> nomes = etapas.findByFluxoIdAndTenantIdOrderByOrdem(fluxoId, tenantId).stream().collect(Collectors.toMap(Etapa::getId, Etapa::getNome));
        return repo.findByFluxoIdAndTenantIdOrderByIdAsc(fluxoId, tenantId).stream().map(r -> dto(r, nomes)).toList();
    }

    @Transactional
    public RegraDto criar(Long tenantId, Long fluxoId, RegraReq req) {
        exigirFluxo(tenantId, fluxoId);
        if (req.evento() == null) throw new NegocioException("Informe quando a regra dispara");
        if (req.tipo() == null) throw new NegocioException("Informe o que a regra faz");
        Long etapaId = null;
        if (req.evento() == EventoAutomacao.ENTRAR_ETAPA) {
            if (req.etapaId() == null) throw new NegocioException("Informe a etapa");
            etapaId = etapas.findByIdAndTenantId(req.etapaId(), tenantId).filter(e -> e.getFluxoId().equals(fluxoId))
                    .orElseThrow(() -> new NaoEncontradoException("Etapa")).getId();
        }
        if (repo.contar(tenantId, fluxoId, req.evento(), etapaId) >= MAXIMO_POR_ETAPA) {
            throw new NegocioException("Cada etapa aceita até " + MAXIMO_POR_ETAPA + " automações");
        }
        if (req.tipo() == TipoAutomacao.GERAR_DOCUMENTO && repo.contarDoTipo(tenantId, fluxoId, req.evento(), etapaId, req.tipo()) > 0) {
            throw new NegocioException("Esta etapa já gera o documento");
        }
        AutomacaoRegra r = new AutomacaoRegra();
        r.setTenantId(tenantId);
        r.setFluxoId(fluxoId);
        r.setEtapaId(etapaId);
        r.setEvento(req.evento());
        r.setTipo(req.tipo());
        r.setAtivo(req.ativo() == null || req.ativo());
        aplicar(r, req);
        return dto(repo.save(r), nomesDoFluxo(tenantId, fluxoId));
    }

    /** Evento, etapa e tipo não mudam; o resto sim. Segredo: null mantém, vazio apaga. */
    @Transactional
    public RegraDto atualizar(Long tenantId, Long fluxoId, Long id, RegraReq req) {
        exigirFluxo(tenantId, fluxoId);
        AutomacaoRegra r = repo.findByIdAndFluxoIdAndTenantId(id, fluxoId, tenantId).orElseThrow(() -> new NaoEncontradoException("Automação"));
        if (req.ativo() != null) r.setAtivo(req.ativo());
        aplicar(r, new RegraReq(r.getEvento(), r.getEtapaId(), r.getTipo(),
                req.url() != null ? req.url() : r.getUrl(), req.segredo(), null));
        return dto(repo.save(r), nomesDoFluxo(tenantId, fluxoId));
    }

    @Transactional
    public void apagar(Long tenantId, Long fluxoId, Long id) {
        exigirFluxo(tenantId, fluxoId);
        repo.delete(repo.findByIdAndFluxoIdAndTenantId(id, fluxoId, tenantId).orElseThrow(() -> new NaoEncontradoException("Automação")));
    }

    @Transactional(readOnly = true)
    public List<ExecucaoDto> execucoes(Long processoId, Long tenantId) {
        return execucoes.findTop20ByProcessoIdAndTenantIdOrderByIdDesc(processoId, tenantId).stream()
                .map(x -> new ExecucaoDto(x.getId(), x.getTipo(), x.getEvento(), x.getEtapaNome(), x.getStatus(), x.getMensagem(), x.getExecutadoEm())).toList();
    }

    /** Valida os campos do tipo da regra e grava só os que valem para ele. */
    private void aplicar(AutomacaoRegra r, RegraReq req) {
        switch (r.getTipo()) {
            case WEBHOOK -> {
                UrlWebhook.validar(req.url(), permitirRedeInterna);
                String segredo = req.segredo();
                if (segredo != null && segredo.length() > 200) throw new NegocioException("O segredo deve ter até 200 caracteres");
                r.setUrl(req.url().trim());
                if (segredo != null) r.setSegredo(segredo.isBlank() ? null : segredo);
            }
            case GERAR_DOCUMENTO -> {
                r.setUrl(null);
                r.setSegredo(null);
            }
        }
    }

    private Map<Long, String> nomesDoFluxo(Long tenantId, Long fluxoId) {
        return etapas.findByFluxoIdAndTenantIdOrderByOrdem(fluxoId, tenantId).stream().collect(Collectors.toMap(Etapa::getId, Etapa::getNome));
    }

    private static RegraDto dto(AutomacaoRegra r, Map<Long, String> nomes) {
        return new RegraDto(r.getId(), r.getFluxoId(), r.getEtapaId(), r.getEtapaId() == null ? null : nomes.get(r.getEtapaId()), r.getEvento(), r.getTipo(),
                r.isAtivo(), r.getUrl(), r.getSegredo() != null && !r.getSegredo().isBlank());
    }
}
