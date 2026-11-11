package br.com.viriato.fluxo;

import br.com.viriato.comum.NaoEncontradoException;
import br.com.viriato.cadastro.Entrega;
import br.com.viriato.cadastro.EntregaRepository;
import br.com.viriato.comum.NegocioException;
import br.com.viriato.processo.ProcessoRepository;
import br.com.viriato.processo.StatusProcesso;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FluxoService {
    private final FluxoRepository fluxos;
    private final EtapaRepository etapas;
    private final TransicaoRepository transicoes;
    private final EntregaRepository entregas;
    private final ProcessoRepository processos;

    public FluxoService(FluxoRepository fluxos, EtapaRepository etapas, TransicaoRepository transicoes,
                        EntregaRepository entregas, ProcessoRepository processos) {
        this.fluxos = fluxos;
        this.etapas = etapas;
        this.transicoes = transicoes;
        this.entregas = entregas;
        this.processos = processos;
    }

    public record EtapaReq(String nome, Integer prazoDias, boolean inicial, boolean finalEtapa) {}
    /** Índices (base 0) na lista de etapas enviada. */
    public record TransicaoReq(int de, int para) {}

    public record EtapaDto(Long id, String nome, int ordem, Integer prazoDias, boolean inicial, boolean finalEtapa) {}
    public record TransicaoDto(Long de, Long para) {}
    public record FluxoDto(Long id, String nome, boolean ativo, List<EtapaDto> etapas, List<TransicaoDto> transicoes) {}

    @Transactional(readOnly = true)
    public List<FluxoDto> listar(Long tenantId) {
        return fluxos.findByTenantIdOrderByNome(tenantId).stream().map(f -> dto(f, tenantId)).toList();
    }

    @Transactional(readOnly = true)
    public FluxoDto obter(Long id, Long tenantId) {
        Fluxo f = fluxos.findByIdAndTenantId(id, tenantId).orElseThrow(() -> new NaoEncontradoException("Fluxo"));
        return dto(f, tenantId);
    }

    @Transactional
    public FluxoDto criar(Long tenantId, String nome, List<EtapaReq> etapasReq, List<TransicaoReq> transReq) {
        if (etapasReq == null || etapasReq.size() < 2) throw new NegocioException("Informe ao menos 2 etapas");
        long iniciais = etapasReq.stream().filter(EtapaReq::inicial).count();
        long finais = etapasReq.stream().filter(EtapaReq::finalEtapa).count();
        if (iniciais != 1) throw new NegocioException("O fluxo precisa ter exatamente 1 etapa inicial");
        if (finais < 1) throw new NegocioException("O fluxo precisa ter ao menos 1 etapa final");
        if (etapasReq.stream().anyMatch(e -> e.nome() == null || e.nome().isBlank()))
            throw new NegocioException("Toda etapa precisa de nome");

        Fluxo f = new Fluxo();
        f.setTenantId(tenantId);
        f.setNome(nome.trim());
        f = fluxos.save(f);

        List<Etapa> salvas = new ArrayList<>();
        for (int i = 0; i < etapasReq.size(); i++) {
            EtapaReq r = etapasReq.get(i);
            if (r.prazoDias() != null && r.prazoDias() < 0) throw new NegocioException("Prazo não pode ser negativo");
            Etapa e = new Etapa();
            e.setTenantId(tenantId);
            e.setFluxoId(f.getId());
            e.setNome(r.nome().trim());
            e.setOrdem(i + 1);
            e.setPrazoDias(r.prazoDias());
            e.setInicial(r.inicial());
            e.setFinalEtapa(r.finalEtapa());
            salvas.add(etapas.save(e));
        }

        List<TransicaoReq> ts = transReq;
        if (ts == null || ts.isEmpty()) { // padrão: sequencial
            ts = new ArrayList<>();
            for (int i = 0; i < salvas.size() - 1; i++) ts.add(new TransicaoReq(i, i + 1));
        }
        for (TransicaoReq t : ts) {
            if (t.de() < 0 || t.para() < 0 || t.de() >= salvas.size() || t.para() >= salvas.size() || t.de() == t.para())
                throw new NegocioException("Transição inválida");
            if (salvas.get(t.de()).isFinalEtapa()) throw new NegocioException("Etapa final não pode ter saída");
            Transicao tr = new Transicao();
            tr.setTenantId(tenantId);
            tr.setFluxoId(f.getId());
            tr.setDeEtapa(salvas.get(t.de()).getId());
            tr.setParaEtapa(salvas.get(t.para()).getId());
            transicoes.save(tr);
        }
        return dto(f, tenantId);
    }

    @Transactional
    public FluxoDto alterarAtivo(Long id, Long tenantId, boolean ativo) {
        Fluxo f = fluxos.findByIdAndTenantId(id, tenantId).orElseThrow(() -> new NaoEncontradoException("Fluxo"));
        f.setAtivo(ativo);
        return dto(fluxos.save(f), tenantId);
    }

    /**
     * Apaga o fluxo com suas etapas e transições. Não apaga se alguma entrega ainda usa o fluxo
     * nem se há processo em andamento nele. Processos já encerrados continuam existindo, sem fluxo.
     */
    @Transactional
    public void apagar(Long id, Long tenantId) {
        Fluxo f = fluxos.findByIdAndTenantId(id, tenantId).orElseThrow(() -> new NaoEncontradoException("Fluxo"));

        List<Entrega> usando = entregas.findByTenantIdAndFluxoIdOrderByNome(tenantId, id);
        if (!usando.isEmpty()) {
            String nomes = usando.stream().limit(3).map(Entrega::getNome).reduce((a, b) -> a + ", " + b).orElse("");
            throw new NegocioException("Não é possível apagar o fluxo \"" + f.getNome() + "\": " + usando.size()
                    + " entrega(s) usam este fluxo (" + nomes + (usando.size() > 3 ? ", …" : "") + "). "
                    + "Troque o fluxo delas ou apague-as antes.");
        }
        long emAndamento = processos.countByTenantIdAndFluxoIdAndStatus(tenantId, id, StatusProcesso.ABERTO);
        if (emAndamento > 0) {
            throw new NegocioException("Não é possível apagar o fluxo \"" + f.getNome() + "\": há " + emAndamento
                    + " processo(s) em andamento nele. Conclua ou cancele esses processos, ou desative o fluxo em vez de apagar.");
        }

        processos.desvincularFluxo(tenantId, id);
        transicoes.deleteAll(transicoes.findByFluxoIdAndTenantId(id, tenantId));
        transicoes.flush();
        etapas.deleteAll(etapas.findByFluxoIdAndTenantIdOrderByOrdem(id, tenantId));
        etapas.flush();
        fluxos.delete(f);
    }

    @Transactional
    public FluxoDto criarFluxoPadrao(Long tenantId) {
        return criar(tenantId, "Fluxo padrão", List.of(
                new EtapaReq("Abertura", 2, true, false),
                new EtapaReq("Análise", 5, false, false),
                new EtapaReq("Revisão", 3, false, false),
                new EtapaReq("Concluído", null, false, true)), null);
    }

    private FluxoDto dto(Fluxo f, Long tenantId) {
        return new FluxoDto(f.getId(), f.getNome(), f.isAtivo(),
                etapas.findByFluxoIdAndTenantIdOrderByOrdem(f.getId(), tenantId).stream()
                        .map(e -> new EtapaDto(e.getId(), e.getNome(), e.getOrdem(), e.getPrazoDias(), e.isInicial(), e.isFinalEtapa()))
                        .toList(),
                transicoes.findByFluxoIdAndTenantId(f.getId(), tenantId).stream()
                        .map(t -> new TransicaoDto(t.getDeEtapa(), t.getParaEtapa())).toList());
    }
}
