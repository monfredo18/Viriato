package br.com.viriato.fluxo;

import br.com.viriato.comum.NaoEncontradoException;
import br.com.viriato.comum.NegocioException;
import br.com.viriato.documento.Documento;
import br.com.viriato.documento.DocumentoRepository;
import br.com.viriato.processo.Processo;
import br.com.viriato.processo.ProcessoRequisito;
import br.com.viriato.processo.ProcessoRequisitoRepository;
import br.com.viriato.usuario.Usuario;
import br.com.viriato.usuario.UsuarioRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Requisitos para SAIR de uma etapa. Configuração (administrador e gestor) e verificação no processo.
 * Um requisito só conta se foi cumprido na passagem atual do processo pela etapa: se o processo voltar
 * e entrar de novo, é preciso cumprir outra vez.
 */
@Service
public class RequisitoService {
    static final int MAXIMO_POR_ETAPA = 10;

    public record RequisitoDto(Long id, TipoRequisito tipo, String descricao) {}

    public record RequisitoStatusDto(Long id, TipoRequisito tipo, String descricao, boolean cumprido,
                                     String cumpridoPor, Instant cumpridoEm, Long documentoId, String documentoNome) {}

    private final EtapaRequisitoRepository repo;
    private final EtapaRepository etapas;
    private final DocumentoRepository documentos;
    private final ProcessoRequisitoRepository confirmacoes;
    private final UsuarioRepository usuarios;

    public RequisitoService(EtapaRequisitoRepository repo, EtapaRepository etapas, DocumentoRepository documentos,
                            ProcessoRequisitoRepository confirmacoes, UsuarioRepository usuarios) {
        this.repo = repo;
        this.etapas = etapas;
        this.documentos = documentos;
        this.confirmacoes = confirmacoes;
        this.usuarios = usuarios;
    }

    // ---------- configuração ----------

    private Etapa etapaDoFluxo(Long tenantId, Long fluxoId, Long etapaId) {
        Etapa e = etapas.findByIdAndTenantId(etapaId, tenantId).orElseThrow(() -> new NaoEncontradoException("Etapa"));
        if (!e.getFluxoId().equals(fluxoId)) throw new NaoEncontradoException("Etapa");
        return e;
    }

    private static String limpar(String descricao) {
        String d = descricao == null ? "" : descricao.trim();
        if (d.isEmpty()) throw new NegocioException("Descreva o requisito");
        if (d.length() > 200) throw new NegocioException("A descrição do requisito deve ter até 200 caracteres");
        return d;
    }

    @Transactional
    public RequisitoDto criar(Long tenantId, Long fluxoId, Long etapaId, TipoRequisito tipo, String descricao) {
        Etapa e = etapaDoFluxo(tenantId, fluxoId, etapaId);
        if (tipo == null) throw new NegocioException("Informe o tipo do requisito");
        if (e.isFinalEtapa()) throw new NegocioException("A etapa final não tem saída, então não tem requisitos");
        long atuais = repo.countByEtapaIdAndTenantId(etapaId, tenantId);
        if (atuais >= MAXIMO_POR_ETAPA) throw new NegocioException("Cada etapa aceita até " + MAXIMO_POR_ETAPA + " requisitos");
        EtapaRequisito r = new EtapaRequisito();
        r.setTenantId(tenantId);
        r.setEtapaId(etapaId);
        r.setTipo(tipo);
        r.setDescricao(limpar(descricao));
        r.setOrdem((int) atuais + 1);
        return dto(repo.save(r));
    }

    @Transactional
    public RequisitoDto renomear(Long tenantId, Long fluxoId, Long etapaId, Long id, String descricao) {
        etapaDoFluxo(tenantId, fluxoId, etapaId);
        EtapaRequisito r = repo.findByIdAndTenantId(id, tenantId).filter(x -> x.getEtapaId().equals(etapaId))
                .orElseThrow(() -> new NaoEncontradoException("Requisito"));
        r.setDescricao(limpar(descricao));
        return dto(repo.save(r));
    }

    @Transactional
    public void apagar(Long tenantId, Long fluxoId, Long etapaId, Long id) {
        etapaDoFluxo(tenantId, fluxoId, etapaId);
        EtapaRequisito r = repo.findByIdAndTenantId(id, tenantId).filter(x -> x.getEtapaId().equals(etapaId))
                .orElseThrow(() -> new NaoEncontradoException("Requisito"));
        repo.delete(r);   // confirmações somem junto (FK em cascata) e documentos apenas perdem o vínculo
    }

    static RequisitoDto dto(EtapaRequisito r) { return new RequisitoDto(r.getId(), r.getTipo(), r.getDescricao()); }

    /** Requisitos de várias etapas de uma vez, agrupados por etapa. */
    @Transactional(readOnly = true)
    public Map<Long, List<RequisitoDto>> porEtapa(List<Long> etapaIds, Long tenantId) {
        Map<Long, List<RequisitoDto>> mapa = new HashMap<>();
        if (etapaIds.isEmpty()) return mapa;
        for (EtapaRequisito r : repo.findByEtapaIdInAndTenantIdOrderByOrdemAscIdAsc(etapaIds, tenantId)) {
            mapa.computeIfAbsent(r.getEtapaId(), k -> new ArrayList<>()).add(dto(r));
        }
        return mapa;
    }

    // ---------- situação no processo ----------

    private static Instant desde(Processo p) { return p.getEtapaDesde() != null ? p.getEtapaDesde() : p.getCriadoEm(); }

    /** Requisitos da etapa atual do processo e se cada um já foi cumprido nesta passagem. */
    @Transactional(readOnly = true)
    public List<RequisitoStatusDto> situacao(Processo p) {
        if (p.getEtapaAtualId() == null) return List.of();
        List<EtapaRequisito> reqs = repo.findByEtapaIdAndTenantIdOrderByOrdemAscIdAsc(p.getEtapaAtualId(), p.getTenantId());
        if (reqs.isEmpty()) return List.of();
        Instant desde = desde(p);

        Map<Long, Documento> docPorRequisito = new HashMap<>();
        for (Documento d : documentos.findByProcessoIdAndTenantIdAndRequisitoIdNotNullOrderByIdDesc(p.getId(), p.getTenantId())) {
            if (!d.getCriadoEm().isBefore(desde)) docPorRequisito.putIfAbsent(d.getRequisitoId(), d);   // o mais recente
        }
        Map<Long, ProcessoRequisito> conf = confirmacoes.findByProcessoIdAndTenantId(p.getId(), p.getTenantId()).stream()
                .filter(c -> !c.getCumpridoEm().isBefore(desde))
                .collect(Collectors.toMap(ProcessoRequisito::getRequisitoId, Function.identity(), (a, b) -> a));
        Map<Long, String> nomes = usuarios.findAllById(conf.values().stream().map(ProcessoRequisito::getCumpridoPor)
                        .filter(x -> x != null).toList()).stream()
                .collect(Collectors.toMap(Usuario::getId, Usuario::getNome));

        List<RequisitoStatusDto> saida = new ArrayList<>();
        for (EtapaRequisito r : reqs) {
            if (r.getTipo() == TipoRequisito.DOCUMENTO) {
                Documento d = docPorRequisito.get(r.getId());
                saida.add(new RequisitoStatusDto(r.getId(), r.getTipo(), r.getDescricao(), d != null,
                        d == null ? null : nomes.get(d.getEnviadoPor()), d == null ? null : d.getCriadoEm(),
                        d == null ? null : d.getId(), d == null ? null : d.getNomeOriginal()));
            } else {
                ProcessoRequisito c = conf.get(r.getId());
                saida.add(new RequisitoStatusDto(r.getId(), r.getTipo(), r.getDescricao(), c != null,
                        c == null ? null : nomes.get(c.getCumpridoPor()), c == null ? null : c.getCumpridoEm(), null, null));
            }
        }
        return saida;
    }

    /** Recusa o avanço enquanto houver requisito pendente na etapa de origem. */
    @Transactional(readOnly = true)
    public void exigirCumpridos(Processo p, Etapa origem) {
        List<String> pendentes = situacao(p).stream().filter(s -> !s.cumprido())
                .map(s -> (s.tipo() == TipoRequisito.DOCUMENTO ? "anexar documento — " : "confirmar — ") + s.descricao())
                .toList();
        if (!pendentes.isEmpty()) {
            throw new NegocioException("Antes de avançar, cumpra os requisitos da etapa \"" + origem.getNome() + "\": "
                    + String.join("; ", pendentes) + ".");
        }
    }

    /** O requisito precisa pertencer à etapa atual do processo (e ser do tipo esperado, se informado). */
    @Transactional(readOnly = true)
    public EtapaRequisito daEtapaAtual(Processo p, Long requisitoId, TipoRequisito esperado) {
        EtapaRequisito r = repo.findByIdAndTenantId(requisitoId, p.getTenantId()).orElseThrow(() -> new NaoEncontradoException("Requisito"));
        if (!r.getEtapaId().equals(p.getEtapaAtualId())) throw new NegocioException("Este requisito não é da etapa atual do processo");
        if (esperado != null && r.getTipo() != esperado) {
            throw new NegocioException(esperado == TipoRequisito.DOCUMENTO
                    ? "Este requisito é uma confirmação, não um documento" : "Este requisito exige um documento, não uma confirmação");
        }
        return r;
    }
}
