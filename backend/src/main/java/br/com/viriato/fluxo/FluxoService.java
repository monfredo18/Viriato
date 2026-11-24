package br.com.viriato.fluxo;

import br.com.viriato.comum.NaoEncontradoException;
import br.com.viriato.cadastro.Entrega;
import br.com.viriato.cadastro.EntregaRepository;
import br.com.viriato.comum.NegocioException;
import br.com.viriato.processo.ProcessoRepository;
import br.com.viriato.processo.StatusProcesso;
import br.com.viriato.usuario.Papel;
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
    private final RequisitoService requisitos;

    public FluxoService(FluxoRepository fluxos, EtapaRepository etapas, TransicaoRepository transicoes,
                        EntregaRepository entregas, ProcessoRepository processos, RequisitoService requisitos) {
        this.fluxos = fluxos;
        this.etapas = etapas;
        this.transicoes = transicoes;
        this.entregas = entregas;
        this.processos = processos;
        this.requisitos = requisitos;
    }

    public record EtapaReq(String nome, Integer prazoDias, boolean inicial, boolean finalEtapa) {}
    /** Índices (base 0) na lista de etapas enviada. */
    public record TransicaoReq(int de, int para) {}

    public record EtapaDto(Long id, String nome, int ordem, Integer prazoDias, boolean inicial, boolean finalEtapa,
                           List<RequisitoService.RequisitoDto> requisitos, Papel papelMinimo) {
        public static EtapaDto de(Etapa e, List<RequisitoService.RequisitoDto> requisitos) {
            return new EtapaDto(e.getId(), e.getNome(), e.getOrdem(), e.getPrazoDias(), e.isInicial(), e.isFinalEtapa(),
                    requisitos == null ? List.of() : requisitos, e.getPapelMinimo());
        }
    }
    public record TransicaoDto(Long de, Long para) {}
    public record FluxoDto(Long id, String nome, boolean ativo, List<EtapaDto> etapas, List<TransicaoDto> transicoes) {
        public record EtapaResumo(Long id, Papel papelMinimo) {}
    }

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
            e.setPrazoDias(r.finalEtapa() ? r.prazoDias() : null);   // só a etapa de conclusão tem prazo
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

    /** Define quem pode mover processos PARA a etapa (os demais pedem aprovação). */
    @Transactional
    public FluxoDto.EtapaResumo definirPapelMinimo(Long tenantId, Long fluxoId, Long etapaId, Papel papelMinimo) {
        if (papelMinimo == null) throw new NegocioException("Informe quem pode mover para a etapa");
        Etapa e = etapas.findByIdAndTenantId(etapaId, tenantId).filter(x -> x.getFluxoId().equals(fluxoId))
                .orElseThrow(() -> new NaoEncontradoException("Etapa"));
        e.setPapelMinimo(papelMinimo);
        etapas.save(e);
        return new FluxoDto.EtapaResumo(e.getId(), e.getPapelMinimo());
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
    public FluxoDto criarFluxoPadrao(Long tenantId) { return criarFluxoPadrao(tenantId, "Fluxo padrão"); }

    @Transactional
    public FluxoDto criarFluxoPadrao(Long tenantId, String nome) {
        return criar(tenantId, nome, List.of(
                new EtapaReq("Abertura", null, true, false),
                new EtapaReq("Análise", null, false, false),
                new EtapaReq("Revisão", null, false, false),
                new EtapaReq("Concluído", 10, false, true)), null);
    }

    // ---------- editar as etapas de um fluxo existente ----------

    private Fluxo fluxoDaEmpresa(Long tenantId, Long fluxoId) {
        return fluxos.findByIdAndTenantId(fluxoId, tenantId).orElseThrow(() -> new NaoEncontradoException("Fluxo"));
    }

    private Etapa etapaDoFluxo(Long tenantId, Long fluxoId, Long etapaId) {
        return etapas.findByIdAndTenantId(etapaId, tenantId).filter(e -> e.getFluxoId().equals(fluxoId))
                .orElseThrow(() -> new NaoEncontradoException("Etapa"));
    }

    /** Fluxo em sequência: cada etapa leva só à seguinte. Os outros têm transições personalizadas (não lineares). */
    private boolean ehSequencial(Long tenantId, Long fluxoId, List<Etapa> lista) {
        var existentes = transicoes.findByFluxoIdAndTenantId(fluxoId, tenantId).stream()
                .map(t -> t.getDeEtapa() + ">" + t.getParaEtapa()).collect(java.util.stream.Collectors.toSet());
        var esperadas = new java.util.HashSet<String>();
        for (int i = 0; i + 1 < lista.size(); i++) if (!lista.get(i).isFinalEtapa()) esperadas.add(lista.get(i).getId() + ">" + lista.get(i + 1).getId());
        return existentes.equals(esperadas);
    }

    private void renumerar(List<Etapa> ordenadas) {
        for (int i = 0; i < ordenadas.size(); i++) { ordenadas.get(i).setOrdem(i + 1); etapas.save(ordenadas.get(i)); }
    }

    private void ligar(Long tenantId, Long fluxoId, Long de, Long para) {
        if (de.equals(para) || transicoes.existsByDeEtapaAndParaEtapaAndTenantId(de, para, tenantId)) return;
        Transicao t = new Transicao();
        t.setTenantId(tenantId);
        t.setFluxoId(fluxoId);
        t.setDeEtapa(de);
        t.setParaEtapa(para);
        transicoes.save(t);
    }

    /** Renumera as etapas na ordem dada e religa as transições em sequência. */
    private void religar(Long tenantId, Long fluxoId, List<Etapa> ordenadas) {
        transicoes.deleteAll(transicoes.findByFluxoIdAndTenantId(fluxoId, tenantId));
        transicoes.flush();
        for (int i = 0; i < ordenadas.size(); i++) {
            ordenadas.get(i).setOrdem(i + 1);
            etapas.save(ordenadas.get(i));
        }
        for (int i = 0; i + 1 < ordenadas.size(); i++) {
            if (ordenadas.get(i).isFinalEtapa()) continue;
            Transicao t = new Transicao();
            t.setTenantId(tenantId);
            t.setFluxoId(fluxoId);
            t.setDeEtapa(ordenadas.get(i).getId());
            t.setParaEtapa(ordenadas.get(i + 1).getId());
            transicoes.save(t);
        }
    }

    /** Nova etapa intermediária, antes da etapa indicada (por padrão, antes da primeira etapa final). Nunca antes da inicial. */
    @Transactional
    public FluxoDto adicionarEtapa(Long tenantId, Long fluxoId, String nome, Long antesDeEtapaId) {
        fluxoDaEmpresa(tenantId, fluxoId);
        if (nome == null || nome.isBlank()) throw new NegocioException("Toda etapa precisa de nome");
        List<Etapa> lista = new ArrayList<>(etapas.findByFluxoIdAndTenantIdOrderByOrdem(fluxoId, tenantId));
        boolean sequencial = ehSequencial(tenantId, fluxoId, lista);
        int posicao = -1;
        if (antesDeEtapaId != null) {
            Etapa ref = etapaDoFluxo(tenantId, fluxoId, antesDeEtapaId);
            if (ref.isInicial()) throw new NegocioException("Não dá para criar uma etapa antes da etapa inicial");
            posicao = lista.indexOf(lista.stream().filter(e -> e.getId().equals(ref.getId())).findFirst().orElseThrow());
        } else {
            for (int i = 0; i < lista.size(); i++) if (lista.get(i).isFinalEtapa()) { posicao = i; break; }
        }
        if (posicao < 0) posicao = lista.size();
        Etapa nova = new Etapa();
        nova.setTenantId(tenantId);
        nova.setFluxoId(fluxoId);
        nova.setNome(nome.trim());
        nova.setOrdem(posicao + 1);
        nova = etapas.save(nova);
        lista.add(posicao, nova);
        if (sequencial) religar(tenantId, fluxoId, lista);
        else {   // fluxo personalizado: mantém as transições e encaixa a nova etapa entre a anterior e a seguinte
            renumerar(lista);
            ligar(tenantId, fluxoId, lista.get(posicao - 1).getId(), nova.getId());
            ligar(tenantId, fluxoId, nova.getId(), lista.get(posicao + 1).getId());
        }
        return dto(fluxoDaEmpresa(tenantId, fluxoId), tenantId);
    }

    /** Renomeia a etapa e, na etapa de conclusão, define o prazo para concluir (em dias; vazio = sem prazo). */
    @Transactional
    public FluxoDto alterarEtapa(Long tenantId, Long fluxoId, Long etapaId, String nome, Integer prazoDias, boolean mudarPrazo) {
        Etapa e = etapaDoFluxo(tenantId, fluxoId, etapaId);
        if (nome != null) {
            if (nome.isBlank()) throw new NegocioException("Toda etapa precisa de nome");
            e.setNome(nome.trim());
        }
        if (mudarPrazo) {
            if (!e.isFinalEtapa()) throw new NegocioException("Só a etapa de conclusão tem prazo");
            if (prazoDias != null && prazoDias < 0) throw new NegocioException("Prazo não pode ser negativo");
            e.setPrazoDias(prazoDias);
        }
        etapas.save(e);
        return dto(fluxoDaEmpresa(tenantId, fluxoId), tenantId);
    }

    /** Remove uma etapa intermediária (nunca a inicial nem a final) que não tenha processo em andamento. */
    @Transactional
    public FluxoDto removerEtapa(Long tenantId, Long fluxoId, Long etapaId) {
        Etapa e = etapaDoFluxo(tenantId, fluxoId, etapaId);
        if (e.isInicial() || e.isFinalEtapa()) throw new NegocioException("A etapa inicial e a de conclusão não podem ser removidas");
        long dentro = processos.countByTenantIdAndEtapaAtualIdAndStatus(tenantId, etapaId, StatusProcesso.ABERTO);
        if (dentro > 0) {
            throw new NegocioException("Há " + dentro + " processo(s) em andamento na etapa \"" + e.getNome() + "\". Mova-os para outra etapa antes de removê-la.");
        }
        List<Etapa> lista = new ArrayList<>(etapas.findByFluxoIdAndTenantIdOrderByOrdem(fluxoId, tenantId));
        boolean sequencial = ehSequencial(tenantId, fluxoId, lista);
        lista.removeIf(x -> x.getId().equals(etapaId));
        processos.desvincularEtapa(tenantId, etapaId);
        if (sequencial) {
            transicoes.deleteAll(transicoes.findByFluxoIdAndTenantId(fluxoId, tenantId));
            transicoes.flush();
            etapas.delete(e);
            etapas.flush();
            religar(tenantId, fluxoId, lista);
        } else {   // fluxo personalizado: quem entrava na etapa removida passa a entrar em quem ela alimentava
            var todas = transicoes.findByFluxoIdAndTenantId(fluxoId, tenantId);
            var entradas = todas.stream().filter(t -> t.getParaEtapa().equals(etapaId)).map(Transicao::getDeEtapa).toList();
            var saidas = todas.stream().filter(t -> t.getDeEtapa().equals(etapaId)).map(Transicao::getParaEtapa).toList();
            transicoes.deleteAll(todas.stream().filter(t -> t.getDeEtapa().equals(etapaId) || t.getParaEtapa().equals(etapaId)).toList());
            transicoes.flush();
            etapas.delete(e);
            etapas.flush();
            for (Long de : entradas) for (Long para : saidas) ligar(tenantId, fluxoId, de, para);
            renumerar(lista);
        }
        return dto(fluxoDaEmpresa(tenantId, fluxoId), tenantId);
    }

    public record TransicaoIds(Long de, Long para) {}

    /** Reordena as etapas intermediárias; a inicial continua primeiro e as de conclusão por último. Em fluxo em sequência, religa as transições. */
    @Transactional
    public FluxoDto reordenar(Long tenantId, Long fluxoId, List<Long> intermediariasNaOrdem) {
        fluxoDaEmpresa(tenantId, fluxoId);
        List<Etapa> lista = new ArrayList<>(etapas.findByFluxoIdAndTenantIdOrderByOrdem(fluxoId, tenantId));
        boolean sequencial = ehSequencial(tenantId, fluxoId, lista);
        List<Etapa> inicial = lista.stream().filter(Etapa::isInicial).toList();
        List<Etapa> finais = lista.stream().filter(Etapa::isFinalEtapa).toList();
        List<Etapa> meio = lista.stream().filter(e -> !e.isInicial() && !e.isFinalEtapa()).toList();
        var porId = meio.stream().collect(java.util.stream.Collectors.toMap(Etapa::getId, e -> e));
        if (intermediariasNaOrdem == null || intermediariasNaOrdem.size() != meio.size() || !porId.keySet().equals(new java.util.HashSet<>(intermediariasNaOrdem))) {
            throw new NegocioException("Informe todas as etapas intermediárias, cada uma uma vez (a inicial e as de conclusão não mudam de lugar)");
        }
        List<Etapa> nova = new ArrayList<>(inicial);
        intermediariasNaOrdem.forEach(id -> nova.add(porId.get(id)));
        nova.addAll(finais);
        if (sequencial) religar(tenantId, fluxoId, nova); else renumerar(nova);
        return dto(fluxoDaEmpresa(tenantId, fluxoId), tenantId);
    }

    /**
     * Define todas as transições do fluxo (não lineares: uma etapa pode ir para várias, e voltar). Regras: nenhuma etapa leva a si
     * mesma, a de conclusão não tem saída, toda etapa é alcançável a partir da inicial e toda etapa chega a uma conclusão.
     */
    @Transactional
    public FluxoDto definirTransicoes(Long tenantId, Long fluxoId, List<TransicaoIds> novas) {
        fluxoDaEmpresa(tenantId, fluxoId);
        List<Etapa> lista = etapas.findByFluxoIdAndTenantIdOrderByOrdem(fluxoId, tenantId);
        var porId = lista.stream().collect(java.util.stream.Collectors.toMap(Etapa::getId, e -> e));
        java.util.Set<String> vistos = new java.util.LinkedHashSet<>();
        java.util.Map<Long, java.util.Set<Long>> saidas = new java.util.HashMap<>();
        java.util.Map<Long, java.util.Set<Long>> entradas = new java.util.HashMap<>();
        for (TransicaoIds t : novas) {
            if (t.de() == null || t.para() == null || !porId.containsKey(t.de()) || !porId.containsKey(t.para())) throw new NegocioException("Transição com etapa que não é deste fluxo");
            if (t.de().equals(t.para())) throw new NegocioException("Uma etapa não pode levar a ela mesma");
            if (porId.get(t.de()).isFinalEtapa()) throw new NegocioException("A etapa \"" + porId.get(t.de()).getNome() + "\" é de conclusão e não pode ter saída");
            if (porId.get(t.para()).isInicial()) throw new NegocioException("Nenhuma etapa pode levar de volta à etapa inicial");
            if (!vistos.add(t.de() + ">" + t.para())) continue;
            saidas.computeIfAbsent(t.de(), k -> new java.util.HashSet<>()).add(t.para());
            entradas.computeIfAbsent(t.para(), k -> new java.util.HashSet<>()).add(t.de());
        }
        Etapa ini = lista.stream().filter(Etapa::isInicial).findFirst().orElseThrow(() -> new NegocioException("Fluxo sem etapa inicial"));
        var alcancaveis = percorrer(ini.getId(), saidas);
        for (Etapa e : lista) if (!alcancaveis.contains(e.getId())) throw new NegocioException("A etapa \"" + e.getNome() + "\" não é alcançável a partir da etapa inicial");
        java.util.Set<Long> chegamNoFim = new java.util.HashSet<>();
        for (Etapa f : lista) if (f.isFinalEtapa()) chegamNoFim.addAll(percorrer(f.getId(), entradas));
        for (Etapa e : lista) if (!chegamNoFim.contains(e.getId())) throw new NegocioException("A etapa \"" + e.getNome() + "\" não leva a nenhuma etapa de conclusão");
        transicoes.deleteAll(transicoes.findByFluxoIdAndTenantId(fluxoId, tenantId));
        transicoes.flush();
        for (String k : vistos) {
            String[] p = k.split(">");
            ligar(tenantId, fluxoId, Long.valueOf(p[0]), Long.valueOf(p[1]));
        }
        return dto(fluxoDaEmpresa(tenantId, fluxoId), tenantId);
    }

    private static java.util.Set<Long> percorrer(Long origem, java.util.Map<Long, java.util.Set<Long>> grafo) {
        java.util.Set<Long> vistos = new java.util.HashSet<>();
        java.util.Deque<Long> pilha = new java.util.ArrayDeque<>(List.of(origem));
        while (!pilha.isEmpty()) {
            Long x = pilha.pop();
            if (vistos.add(x)) pilha.addAll(grafo.getOrDefault(x, java.util.Set.of()));
        }
        return vistos;
    }

    private FluxoDto dto(Fluxo f, Long tenantId) {
        List<Etapa> lista = etapas.findByFluxoIdAndTenantIdOrderByOrdem(f.getId(), tenantId);
        var reqs = requisitos.porEtapa(lista.stream().map(Etapa::getId).toList(), tenantId);
        return new FluxoDto(f.getId(), f.getNome(), f.isAtivo(),
                lista.stream().map(e -> EtapaDto.de(e, reqs.get(e.getId()))).toList(),
                transicoes.findByFluxoIdAndTenantId(f.getId(), tenantId).stream()
                        .map(t -> new TransicaoDto(t.getDeEtapa(), t.getParaEtapa())).toList());
    }
}
