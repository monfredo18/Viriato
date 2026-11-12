package br.com.viriato.indicador;

import br.com.viriato.cadastro.Setor;
import br.com.viriato.cadastro.SetorRepository;
import br.com.viriato.comum.Datas;
import br.com.viriato.fluxo.Etapa;
import br.com.viriato.fluxo.EtapaRepository;
import br.com.viriato.fluxo.Fluxo;
import br.com.viriato.fluxo.FluxoRepository;
import br.com.viriato.processo.ProcessoHistorico;
import br.com.viriato.processo.ProcessoHistoricoRepository;
import br.com.viriato.processo.ProcessoRepository;
import br.com.viriato.processo.StatusProcesso;
import br.com.viriato.seguranca.UsuarioLogado;
import br.com.viriato.usuario.Papel;
import br.com.viriato.usuario.Usuario;
import br.com.viriato.usuario.UsuarioRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Indicadores para o painel. A abrangência vem SEMPRE do papel de quem pede: o administrador vê todos os setores
 * (e pode filtrar por um); gestor e usuário veem só o próprio setor, como na lista de processos. "Atrasado" é
 * processo em andamento com prazo da etapa vencido. Encerrados (concluídos e cancelados) contam dentro do período.
 */
@Service
public class IndicadorService {
    static final int PERIODO_PADRAO = 90;
    static final int PERIODO_MAXIMO = 730;
    private static final int LOTE = 500;

    public record Resumo(long abertos, long atrasados, long concluidos, long cancelados, Double tempoMedioConclusaoDias) {}
    public record PorSetor(Long setorId, String setor, long abertos, long atrasados, long concluidos, long cancelados) {}
    /** {@code mediaHoras}: tempo médio que os processos ficaram na etapa; {@code foraDoPrazo}: passagens que passaram do prazo da etapa. */
    public record PorEtapa(String fluxo, String etapa, Integer prazoDias, long passagens, double mediaHoras, long foraDoPrazo) {}
    public record PorFuncionario(Long usuarioId, String nome, String setor, long abertos, long atrasados, long concluidos) {}
    public record Painel(String escopo, boolean todosOsSetores, int periodoDias, Instant geradoEm, Resumo resumo,
                         List<PorSetor> setores, List<PorEtapa> etapas, List<PorFuncionario> funcionarios) {}

    private final ProcessoRepository processos;
    private final ProcessoHistoricoRepository historicos;
    private final UsuarioRepository usuarios;
    private final SetorRepository setoresRepo;
    private final FluxoRepository fluxos;
    private final EtapaRepository etapas;

    public IndicadorService(ProcessoRepository processos, ProcessoHistoricoRepository historicos, UsuarioRepository usuarios,
                            SetorRepository setoresRepo, FluxoRepository fluxos, EtapaRepository etapas) {
        this.processos = processos;
        this.historicos = historicos;
        this.usuarios = usuarios;
        this.setoresRepo = setoresRepo;
        this.fluxos = fluxos;
        this.etapas = etapas;
    }

    @Transactional(readOnly = true)
    public Painel gerar(UsuarioLogado quem, Integer dias, Long setorFiltro) {
        Long tid = quem.tenantId();
        int periodo = dias == null ? PERIODO_PADRAO : Math.max(1, Math.min(dias, PERIODO_MAXIMO));
        Instant agora = Instant.now();
        Instant desde = agora.minus(Duration.ofDays(periodo));
        LocalDate hoje = Datas.hoje();

        boolean admin = quem.papel() == Papel.ADMIN;
        Long setorEscopo = setorFiltro;
        if (!admin) {   // gestor e usuário: só o próprio setor, qualquer filtro enviado é ignorado
            setorEscopo = usuarios.findByIdAndTenantId(quem.id(), tid).map(Usuario::getSetorId).orElse(null);
            if (setorEscopo == null) {
                return new Painel("Seu usuário não tem setor definido", false, periodo, agora,
                        new Resumo(0, 0, 0, 0, null), List.of(), List.of(), List.of());
            }
        }
        // processos de funcionário apagado ficam restritos a gestor e administrador
        List<LinhaProcesso> linhas = processos.paraIndicadores(tid, setorEscopo, desde, quem.papel() == Papel.USUARIO);

        Map<Long, String> nomesSetor = setoresRepo.findByTenantIdOrderByNome(tid).stream().collect(Collectors.toMap(Setor::getId, Setor::getNome));
        String escopo = setorEscopo == null ? "Todos os setores" : "Setor " + nomesSetor.getOrDefault(setorEscopo, "(removido)");

        return new Painel(escopo, setorEscopo == null, periodo, agora, resumo(linhas, hoje),
                porSetor(linhas, hoje, setorEscopo, nomesSetor, tid),
                porEtapa(linhas, desde, tid),
                porFuncionario(linhas, hoje, setorEscopo, nomesSetor, tid));
    }

    static boolean atrasado(LinhaProcesso l, LocalDate hoje) {
        return l.status() == StatusProcesso.ABERTO && l.prazoEtapa() != null && l.prazoEtapa().isBefore(hoje);
    }

    private Resumo resumo(List<LinhaProcesso> linhas, LocalDate hoje) {
        long abertos = 0, atrasados = 0, concluidos = 0, cancelados = 0;
        double somaDias = 0;
        for (LinhaProcesso l : linhas) {
            switch (l.status()) {
                case ABERTO -> { abertos++; if (atrasado(l, hoje)) atrasados++; }
                case CONCLUIDO -> {
                    concluidos++;
                    somaDias += Duration.between(l.criadoEm(), l.atualizadoEm()).toSeconds() / 86400.0;
                }
                case CANCELADO -> cancelados++;
            }
        }
        return new Resumo(abertos, atrasados, concluidos, cancelados, concluidos == 0 ? null : somaDias / concluidos);
    }

    private List<PorSetor> porSetor(List<LinhaProcesso> linhas, LocalDate hoje, Long setorEscopo, Map<Long, String> nomes, Long tid) {
        Map<Long, long[]> mapa = new LinkedHashMap<>();   // [abertos, atrasados, concluidos, cancelados]
        if (setorEscopo == null) {   // administrador: todos os setores ativos aparecem, mesmo sem processos
            setoresRepo.findByTenantIdOrderByNome(tid).stream().filter(Setor::isAtivo).forEach(s -> mapa.put(s.getId(), new long[4]));
        } else {
            mapa.put(setorEscopo, new long[4]);
        }
        for (LinhaProcesso l : linhas) {
            long[] c = mapa.computeIfAbsent(l.setorId() == null ? -1L : l.setorId(), k -> new long[4]);
            switch (l.status()) {
                case ABERTO -> { c[0]++; if (atrasado(l, hoje)) c[1]++; }
                case CONCLUIDO -> c[2]++;
                case CANCELADO -> c[3]++;
            }
        }
        return mapa.entrySet().stream()
                .map(e -> new PorSetor(e.getKey() < 0 ? null : e.getKey(), e.getKey() < 0 ? "Sem setor" : nomes.getOrDefault(e.getKey(), "(removido)"),
                        e.getValue()[0], e.getValue()[1], e.getValue()[2], e.getValue()[3]))
                .sorted(Comparator.comparingLong(PorSetor::atrasados).reversed().thenComparing(Comparator.comparingLong(PorSetor::abertos).reversed())
                        .thenComparing(PorSetor::setor))
                .toList();
    }

    /**
     * Tempo em cada etapa: cada entrada em etapa (criação ou movimentação) dura até a entrada seguinte. A etapa em que o
     * processo está agora e a etapa final não entram (ainda não terminaram / não têm duração). Só contam passagens
     * terminadas dentro do período.
     */
    private List<PorEtapa> porEtapa(List<LinhaProcesso> linhas, Instant desde, Long tid) {
        Map<Long, LinhaProcesso> porProcesso = linhas.stream().collect(Collectors.toMap(LinhaProcesso::id, l -> l));
        List<Long> ids = new ArrayList<>(porProcesso.keySet());
        Map<Long, List<ProcessoHistorico>> entradas = new HashMap<>();
        for (int i = 0; i < ids.size(); i += LOTE) {
            for (ProcessoHistorico h : historicos.entradasEmEtapa(tid, ids.subList(i, Math.min(ids.size(), i + LOTE)))) {
                entradas.computeIfAbsent(h.getProcessoId(), k -> new ArrayList<>()).add(h);
            }
        }
        // prazo previsto de cada etapa (pelo nome dentro do fluxo), quando o fluxo ainda existe
        Set<Long> fluxoIds = linhas.stream().map(LinhaProcesso::fluxoId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> nomesFluxo = fluxos.findAllById(fluxoIds).stream().collect(Collectors.toMap(Fluxo::getId, Fluxo::getNome));
        Map<String, Integer> prazos = new HashMap<>();
        for (Long fid : fluxoIds) {
            for (Etapa e : etapas.findByFluxoIdAndTenantIdOrderByOrdem(fid, tid)) {
                if (e.getPrazoDias() != null) prazos.put(fid + "|" + e.getNome(), e.getPrazoDias());
            }
        }

        record Acum(String fluxo, String etapa, Integer prazo, long[] n, double[] horas) {}
        Map<String, Acum> acum = new LinkedHashMap<>();
        for (var entry : entradas.entrySet()) {
            LinhaProcesso l = porProcesso.get(entry.getKey());
            List<ProcessoHistorico> hs = entry.getValue();
            for (int i = 0; i + 1 < hs.size(); i++) {
                Instant fim = hs.get(i + 1).getCriadoEm();
                if (fim.isBefore(desde)) continue;
                String etapa = hs.get(i).getParaEtapa();
                if (etapa == null) continue;
                String fluxo = l.fluxoId() == null ? "Fluxo removido" : nomesFluxo.getOrDefault(l.fluxoId(), "Fluxo removido");
                Integer prazo = l.fluxoId() == null ? null : prazos.get(l.fluxoId() + "|" + etapa);
                Acum a = acum.computeIfAbsent(fluxo + "|" + etapa, k -> new Acum(fluxo, etapa, prazo, new long[2], new double[1]));
                double h = Duration.between(hs.get(i).getCriadoEm(), fim).toMillis() / 3_600_000.0;
                a.n()[0]++;
                a.horas()[0] += h;
                if (prazo != null && h > prazo * 24.0) a.n()[1]++;
            }
        }
        return acum.values().stream()
                .map(a -> new PorEtapa(a.fluxo(), a.etapa(), a.prazo(), a.n()[0], a.horas()[0] / a.n()[0], a.n()[1]))
                .sorted(Comparator.comparingDouble(PorEtapa::mediaHoras).reversed())
                .toList();
    }

    private List<PorFuncionario> porFuncionario(List<LinhaProcesso> linhas, LocalDate hoje, Long setorEscopo, Map<Long, String> nomesSetor, Long tid) {
        Map<Long, long[]> carga = new HashMap<>();   // [abertos, atrasados, concluidos]
        for (LinhaProcesso l : linhas) {
            if (l.responsavelId() == null) continue;
            long[] c = carga.computeIfAbsent(l.responsavelId(), k -> new long[3]);
            if (l.status() == StatusProcesso.ABERTO) { c[0]++; if (atrasado(l, hoje)) c[1]++; }
            else if (l.status() == StatusProcesso.CONCLUIDO) c[2]++;
        }
        List<PorFuncionario> saida = new ArrayList<>();
        for (Usuario u : usuarios.findByTenantIdOrderByNome(tid)) {
            long[] c = carga.getOrDefault(u.getId(), new long[3]);
            boolean temCarga = c[0] + c[2] > 0;
            boolean doEscopo = setorEscopo == null ? u.isAtivo() && u.getSetorId() != null : Objects.equals(u.getSetorId(), setorEscopo) && u.isAtivo();
            if (!doEscopo && !temCarga) continue;
            saida.add(new PorFuncionario(u.getId(), u.getNome(), u.getSetorId() == null ? null : nomesSetor.get(u.getSetorId()), c[0], c[1], c[2]));
        }
        saida.sort(Comparator.comparingLong(PorFuncionario::abertos).reversed().thenComparing(Comparator.comparingLong(PorFuncionario::atrasados).reversed())
                .thenComparing(PorFuncionario::nome));
        return saida;
    }
}
