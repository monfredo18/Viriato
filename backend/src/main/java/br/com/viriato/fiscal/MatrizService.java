package br.com.viriato.fiscal;

import br.com.viriato.cadastro.CadastroService;
import br.com.viriato.cadastro.Cliente;
import br.com.viriato.cadastro.ClienteRepository;
import br.com.viriato.cadastro.Entrega;
import br.com.viriato.cadastro.EntregaRepository;
import br.com.viriato.comum.NaoEncontradoException;
import br.com.viriato.comum.NegocioException;
import br.com.viriato.crm.DadosFiscais;
import br.com.viriato.crm.Enums.RegimeTributario;
import br.com.viriato.crm.FichaFiscalService;
import br.com.viriato.fiscal.Enums.*;
import br.com.viriato.seguranca.UsuarioLogado;
import br.com.viriato.usuario.Papel;
import br.com.viriato.usuario.UsuarioRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Matriz tributária: os modelos de obrigação (ligados a uma entrega, que já define fluxo e setor) e o vínculo de cada empresa
 * atendida com os modelos compatíveis com o regime tributário, a UF e os CNAEs dela.
 */
@Service
public class MatrizService {
    private static final ObjectMapper JSON = new ObjectMapper();

    public record ModeloReq(String nome, Long entregaId, Periodicidade periodicidade, ModoVencimento modoVencimento, Integer diaVencimento, Integer mesesDefasagem,
                            List<Integer> mesesAplicaveis, RegraDiaUtil regraDiaUtil, Esfera esfera, List<RegimeTributario> regimesAplicaveis,
                            List<String> ufsAplicaveis, List<String> cnaesAplicaveis, Long responsavelPadraoId, Boolean ativo, List<Long> dependeDeModeloIds) {}

    public record ModeloDto(Long id, String nome, Long entregaId, String entregaNome, Long setorId, Periodicidade periodicidade, ModoVencimento modoVencimento, Integer diaVencimento,
                            int mesesDefasagem, List<Integer> mesesAplicaveis, RegraDiaUtil regraDiaUtil, Esfera esfera, List<RegimeTributario> regimesAplicaveis,
                            List<String> ufsAplicaveis, List<String> cnaesAplicaveis, Long responsavelPadraoId, boolean ativo, List<Long> dependeDeModeloIds, long empresasVinculadas) {}

    /** Modelo de partida (catálogo): a pessoa escolhe a entrega e confere os prazos com a legislação vigente. */
    public record ItemCatalogo(String nome, Periodicidade periodicidade, ModoVencimento modoVencimento, Integer diaVencimento, int mesesDefasagem, List<Integer> mesesAplicaveis,
                               RegraDiaUtil regraDiaUtil, Esfera esfera, List<RegimeTributario> regimesAplicaveis, String observacao) {}

    public record ObrigacaoDaEmpresaDto(Long modeloId, String nome, String entregaNome, Periodicidade periodicidade, Esfera esfera, String vencimento, boolean vinculada,
                                        boolean ativa, boolean compativel, Long responsavelId, boolean personalizado) {}
    public record AtualizaVinculo(Boolean ativa, Long responsavelId, boolean limparResponsavel) {}

    static final List<Integer> TODOS_OS_MESES = List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);
    static final List<ItemCatalogo> CATALOGO = List.of(
            new ItemCatalogo("PGDAS-D (Simples Nacional)", Periodicidade.MENSAL, ModoVencimento.DIA_DO_MES, 20, 1, TODOS_OS_MESES, RegraDiaUtil.ANTECIPAR_DIA_UTIL, Esfera.FEDERAL, List.of(RegimeTributario.SIMPLES_NACIONAL), "Apuração e DAS do mês anterior"),
            new ItemCatalogo("DCTFWeb", Periodicidade.MENSAL, ModoVencimento.DIA_DO_MES, 15, 1, TODOS_OS_MESES, RegraDiaUtil.ANTECIPAR_DIA_UTIL, Esfera.FEDERAL, List.of(RegimeTributario.LUCRO_PRESUMIDO, RegimeTributario.LUCRO_REAL, RegimeTributario.SIMPLES_NACIONAL), "Declaração de débitos previdenciários e de tributos federais"),
            new ItemCatalogo("EFD-Reinf", Periodicidade.MENSAL, ModoVencimento.DIA_DO_MES, 15, 1, TODOS_OS_MESES, RegraDiaUtil.ANTECIPAR_DIA_UTIL, Esfera.FEDERAL, List.of(), "Retenções e informações fiscais"),
            new ItemCatalogo("EFD ICMS/IPI (SPED Fiscal)", Periodicidade.MENSAL, ModoVencimento.DIA_DO_MES, 20, 1, TODOS_OS_MESES, RegraDiaUtil.ANTECIPAR_DIA_UTIL, Esfera.ESTADUAL, List.of(RegimeTributario.LUCRO_PRESUMIDO, RegimeTributario.LUCRO_REAL), "O prazo varia por UF: ajuste conforme o estado"),
            new ItemCatalogo("EFD-Contribuições", Periodicidade.MENSAL, ModoVencimento.ENESIMO_DIA_UTIL, 10, 2, TODOS_OS_MESES, RegraDiaUtil.MANTER_DATA, Esfera.FEDERAL, List.of(RegimeTributario.LUCRO_PRESUMIDO, RegimeTributario.LUCRO_REAL), "10º dia útil do 2º mês seguinte"),
            new ItemCatalogo("eSocial e folha de pagamento", Periodicidade.MENSAL, ModoVencimento.ENESIMO_DIA_UTIL, 5, 1, TODOS_OS_MESES, RegraDiaUtil.MANTER_DATA, Esfera.FEDERAL, List.of(), "5º dia útil do mês seguinte"),
            new ItemCatalogo("FGTS Digital", Periodicidade.MENSAL, ModoVencimento.DIA_DO_MES, 20, 1, TODOS_OS_MESES, RegraDiaUtil.ANTECIPAR_DIA_UTIL, Esfera.FEDERAL, List.of(), "Guia do FGTS do mês anterior"),
            new ItemCatalogo("Fechamento contábil mensal", Periodicidade.MENSAL, ModoVencimento.ULTIMO_DIA_UTIL, null, 1, TODOS_OS_MESES, RegraDiaUtil.MANTER_DATA, Esfera.FEDERAL, List.of(RegimeTributario.LUCRO_PRESUMIDO, RegimeTributario.LUCRO_REAL), "Prazo interno do escritório"),
            new ItemCatalogo("ECD (escrituração contábil digital)", Periodicidade.ANUAL, ModoVencimento.ULTIMO_DIA_UTIL, null, 5, List.of(12), RegraDiaUtil.MANTER_DATA, Esfera.FEDERAL, List.of(RegimeTributario.LUCRO_PRESUMIDO, RegimeTributario.LUCRO_REAL), "Último dia útil de maio do ano seguinte"),
            new ItemCatalogo("ECF (escrituração contábil fiscal)", Periodicidade.ANUAL, ModoVencimento.ULTIMO_DIA_UTIL, null, 7, List.of(12), RegraDiaUtil.MANTER_DATA, Esfera.FEDERAL, List.of(RegimeTributario.LUCRO_PRESUMIDO, RegimeTributario.LUCRO_REAL), "Último dia útil de julho do ano seguinte"));

    private final ObrigacaoModeloRepository modelos;
    private final ClienteObrigacaoRepository vinculos;
    private final ObrigacaoModeloDependenciaRepository dependencias;
    private final ClienteRepository clientes;
    private final EntregaRepository entregas;
    private final UsuarioRepository usuarios;
    private final CadastroService cadastros;
    private final FichaFiscalService fiscal;

    public MatrizService(ObrigacaoModeloRepository modelos, ClienteObrigacaoRepository vinculos, ObrigacaoModeloDependenciaRepository dependencias,
                         ClienteRepository clientes, EntregaRepository entregas, UsuarioRepository usuarios, CadastroService cadastros, FichaFiscalService fiscal) {
        this.modelos = modelos;
        this.vinculos = vinculos;
        this.dependencias = dependencias;
        this.clientes = clientes;
        this.entregas = entregas;
        this.usuarios = usuarios;
        this.cadastros = cadastros;
        this.fiscal = fiscal;
    }

    public List<ItemCatalogo> catalogo() { return CATALOGO; }

    // ---------- modelos ----------

    @Transactional(readOnly = true)
    public List<ModeloDto> listar(UsuarioLogado quem) {
        Long meuSetor = quem.papel() == Papel.ADMIN ? null : usuarios.setorDe(quem);
        Map<Long, Entrega> es = entregas.findByTenantIdOrderByNome(quem.tenantId()).stream().collect(Collectors.toMap(Entrega::getId, Function.identity()));
        Map<Long, List<Long>> deps = dependencias.findByTenantId(quem.tenantId()).stream()
                .collect(Collectors.groupingBy(ObrigacaoModeloDependencia::getModeloId, Collectors.mapping(ObrigacaoModeloDependencia::getPredecessorModeloId, Collectors.toList())));
        return modelos.findByTenantIdOrderByNome(quem.tenantId()).stream()
                .filter(m -> quem.papel() == Papel.ADMIN || (es.get(m.getEntregaId()) != null && meuSetor != null && meuSetor.equals(es.get(m.getEntregaId()).getSetorDonoId())))
                .map(m -> dto(m, es.get(m.getEntregaId()), deps.getOrDefault(m.getId(), List.of()))).toList();
    }

    @Transactional
    public ModeloDto criar(UsuarioLogado quem, ModeloReq r) {
        if (r.entregaId() == null) throw new NegocioException("Escolha a entrega (ela define o fluxo e o setor)");
        Entrega e = cadastros.entregaQueEleMexe(quem, r.entregaId());
        ObrigacaoModelo m = new ObrigacaoModelo();
        m.setTenantId(quem.tenantId());
        m.setEntregaId(e.getId());
        aplicar(m, r, quem.tenantId());
        if (modelos.existsByTenantIdAndEntregaIdAndNomeIgnoreCase(quem.tenantId(), e.getId(), m.getNome())) throw new NegocioException("Já existe uma obrigação com esse nome nesta entrega");
        m = modelos.save(m);
        gravarDependencias(m, r.dependeDeModeloIds());
        vincularCompativeis(m);
        return dto(m, e, dependencias.findByModeloId(m.getId()).stream().map(ObrigacaoModeloDependencia::getPredecessorModeloId).toList());
    }

    @Transactional
    public ModeloDto alterar(UsuarioLogado quem, Long id, ModeloReq r) {
        ObrigacaoModelo m = modeloDoAlcance(quem, id);
        aplicar(m, r, quem.tenantId());
        m = modelos.save(m);
        if (r.dependeDeModeloIds() != null) gravarDependencias(m, r.dependeDeModeloIds());
        vincularCompativeis(m);
        Entrega e = entregas.findByIdAndTenantId(m.getEntregaId(), quem.tenantId()).orElse(null);
        return dto(m, e, dependencias.findByModeloId(m.getId()).stream().map(ObrigacaoModeloDependencia::getPredecessorModeloId).toList());
    }

    @Transactional
    public void apagar(UsuarioLogado quem, Long id) { modelos.delete(modeloDoAlcance(quem, id)); }

    public ObrigacaoModelo modeloDoAlcance(UsuarioLogado quem, Long id) {
        ObrigacaoModelo m = modelos.findByIdAndTenantId(id, quem.tenantId()).orElseThrow(() -> new NaoEncontradoException("Obrigação"));
        cadastros.entregaQueEleMexe(quem, m.getEntregaId());   // gestor só mexe nas do próprio setor
        return m;
    }

    private void aplicar(ObrigacaoModelo m, ModeloReq r, Long tenantId) {
        if (r.nome() != null) {
            if (r.nome().isBlank() || r.nome().length() > 120) throw new NegocioException("O nome da obrigação deve ter de 1 a 120 caracteres");
            m.setNome(r.nome().trim());
        }
        if (m.getNome() == null) throw new NegocioException("Informe o nome da obrigação");
        if (r.periodicidade() != null) m.setPeriodicidade(r.periodicidade());
        if (r.modoVencimento() != null) m.setModoVencimento(r.modoVencimento());
        if (r.diaVencimento() != null) m.setDiaVencimento(r.diaVencimento());
        if (r.mesesDefasagem() != null) {
            if (r.mesesDefasagem() < 0 || r.mesesDefasagem() > 24) throw new NegocioException("A defasagem do vencimento deve ficar entre 0 e 24 meses");
            m.setMesesDefasagem(r.mesesDefasagem());
        }
        if (r.mesesAplicaveis() != null) {
            Set<Integer> ms = new LinkedHashSet<>(r.mesesAplicaveis());
            if (ms.isEmpty() || ms.stream().anyMatch(x -> x == null || x < 1 || x > 12)) throw new NegocioException("Informe os meses de competência (1 a 12)");
            m.setMesesAplicaveis(escrever(ms.stream().sorted().toList()));
        }
        if (r.regraDiaUtil() != null) m.setRegraDiaUtil(r.regraDiaUtil());
        if (r.esfera() != null) m.setEsfera(r.esfera());
        if (r.regimesAplicaveis() != null) m.setRegimesAplicaveis(r.regimesAplicaveis().isEmpty() ? null : escrever(r.regimesAplicaveis().stream().map(Enum::name).toList()));
        if (r.ufsAplicaveis() != null) {
            List<String> ufs = r.ufsAplicaveis().stream().map(u -> u.trim().toUpperCase()).filter(u -> !u.isEmpty()).toList();
            if (ufs.stream().anyMatch(u -> !CalendarioFiscalService.UFS.contains(u))) throw new NegocioException("UF inválida na lista");
            m.setUfsAplicaveis(ufs.isEmpty() ? null : escrever(ufs));
        }
        if (r.cnaesAplicaveis() != null) {
            List<String> cs = r.cnaesAplicaveis().stream().map(c -> c.replaceAll("\\D", "")).filter(c -> !c.isEmpty()).toList();
            if (cs.stream().anyMatch(c -> c.length() > 7)) throw new NegocioException("CNAE com mais de 7 dígitos");
            m.setCnaesAplicaveis(cs.isEmpty() ? null : escrever(cs));
        }
        if (r.responsavelPadraoId() != null) {
            usuarios.findByIdAndTenantId(r.responsavelPadraoId(), tenantId).filter(br.com.viriato.usuario.Usuario::isAtivo).orElseThrow(() -> new NegocioException("O responsável padrão precisa ser um funcionário ativo"));
            m.setResponsavelPadraoId(r.responsavelPadraoId());
        }
        if (r.ativo() != null) m.setAtivo(r.ativo());
        // consistência
        if (m.getPeriodicidade() == null || m.getModoVencimento() == null || m.getRegraDiaUtil() == null || m.getEsfera() == null || m.getMesesAplicaveis() == null) {
            throw new NegocioException("Informe periodicidade, vencimento, regra de dia útil, esfera e meses de competência");
        }
        if (m.getModoVencimento() == ModoVencimento.DIA_DO_MES && (m.getDiaVencimento() == null || m.getDiaVencimento() < 1 || m.getDiaVencimento() > 31)) throw new NegocioException("Informe o dia do vencimento (1 a 31)");
        if (m.getModoVencimento() == ModoVencimento.ENESIMO_DIA_UTIL && (m.getDiaVencimento() == null || m.getDiaVencimento() < 1 || m.getDiaVencimento() > 22)) throw new NegocioException("Informe qual dia útil vence (1º a 22º)");
        if (m.getModoVencimento() == ModoVencimento.ULTIMO_DIA_UTIL) m.setDiaVencimento(null);
    }

    private void gravarDependencias(ObrigacaoModelo m, List<Long> preds) {
        if (preds == null) return;
        dependencias.deleteByModeloId(m.getId());
        dependencias.flush();
        for (Long p : new LinkedHashSet<>(preds)) {
            if (p.equals(m.getId())) throw new NegocioException("Uma obrigação não pode depender de si mesma");
            ObrigacaoModelo pm = modelos.findByIdAndTenantId(p, m.getTenantId()).orElseThrow(() -> new NegocioException("Obrigação predecessora não encontrada"));
            if (dependeDe(pm.getId(), m.getId(), new java.util.HashSet<>())) throw new NegocioException("A dependência criaria um ciclo entre as obrigações");
            ObrigacaoModeloDependencia d = new ObrigacaoModeloDependencia();
            d.setTenantId(m.getTenantId());
            d.setModeloId(m.getId());
            d.setPredecessorModeloId(pm.getId());
            dependencias.save(d);
        }
    }

    private boolean dependeDe(Long modelo, Long alvo, Set<Long> vistos) {
        if (!vistos.add(modelo)) return false;
        for (ObrigacaoModeloDependencia d : dependencias.findByModeloId(modelo)) {
            if (d.getPredecessorModeloId().equals(alvo) || dependeDe(d.getPredecessorModeloId(), alvo, vistos)) return true;
        }
        return false;
    }

    // ---------- compatibilidade e vínculo ----------

    /** O regime, a UF e os CNAEs da empresa atendem aos filtros do modelo (filtro vazio = vale para todos). */
    public boolean compativel(Cliente c, ObrigacaoModelo m) {
        List<String> regimes = lista(m.getRegimesAplicaveis());
        if (!regimes.isEmpty() && (c.getRegimeTributario() == null || !regimes.contains(c.getRegimeTributario().name()))) return false;
        List<String> ufs = lista(m.getUfsAplicaveis());
        if (!ufs.isEmpty() && (c.getUf() == null || !ufs.contains(c.getUf()))) return false;
        List<String> prefixos = lista(m.getCnaesAplicaveis());
        if (!prefixos.isEmpty()) {
            List<String> codigos = new ArrayList<>();
            if (c.getCnaePrincipal() != null) codigos.add(c.getCnaePrincipal());
            fiscal.cnaesSecundarios(c).forEach(x -> codigos.add(x.codigo()));
            if (codigos.stream().noneMatch(cod -> prefixos.stream().anyMatch(cod::startsWith))) return false;
        }
        return true;
    }

    /** Depois de mudar regime, UF ou CNAE da empresa: vincula os modelos compatíveis que ainda não estão vinculados. Não reativa o que alguém desligou. */
    @Transactional
    public int vincularCompativeis(Cliente c) {
        int n = 0;
        for (ObrigacaoModelo m : modelos.findByTenantIdAndAtivoTrueOrderByNome(c.getTenantId())) {
            if (!vinculos.existsByClienteIdAndModeloId(c.getId(), m.getId()) && compativel(c, m)) { criarVinculo(c, m, false); n++; }
        }
        return n;
    }

    /** Depois de criar ou alterar um modelo: vincula as empresas ativas compatíveis. */
    @Transactional
    public int vincularCompativeis(ObrigacaoModelo m) {
        if (!m.isAtivo()) return 0;
        int n = 0;
        for (Cliente c : clientes.findByTenantIdOrderByNome(m.getTenantId())) {
            if (!c.isAtivo()) continue;
            if (!vinculos.existsByClienteIdAndModeloId(c.getId(), m.getId()) && compativel(c, m)) { criarVinculo(c, m, false); n++; }
        }
        return n;
    }

    private void criarVinculo(Cliente c, ObrigacaoModelo m, boolean personalizado) {
        ClienteObrigacao v = new ClienteObrigacao();
        v.setTenantId(c.getTenantId());
        v.setClienteId(c.getId());
        v.setModeloId(m.getId());
        v.setPersonalizado(personalizado);
        vinculos.save(v);
    }

    // ---------- obrigações de uma empresa ----------

    @Transactional(readOnly = true)
    public List<ObrigacaoDaEmpresaDto> daEmpresa(UsuarioLogado quem, Long clienteId) {
        Cliente c = clientes.findByIdAndTenantId(clienteId, quem.tenantId()).orElseThrow(() -> new NaoEncontradoException("Empresa"));
        Map<Long, ClienteObrigacao> vs = vinculos.findByClienteIdAndTenantId(clienteId, quem.tenantId()).stream().collect(Collectors.toMap(ClienteObrigacao::getModeloId, Function.identity()));
        Map<Long, Entrega> es = entregas.findByTenantIdOrderByNome(quem.tenantId()).stream().collect(Collectors.toMap(Entrega::getId, Function.identity()));
        Long meuSetor = quem.papel() == Papel.ADMIN ? null : usuarios.setorDe(quem);
        List<ObrigacaoDaEmpresaDto> out = new ArrayList<>();
        for (ObrigacaoModelo m : modelos.findByTenantIdOrderByNome(quem.tenantId())) {
            Entrega e = es.get(m.getEntregaId());
            if (quem.papel() != Papel.ADMIN && (e == null || meuSetor == null || !meuSetor.equals(e.getSetorDonoId()))) continue;
            ClienteObrigacao v = vs.get(m.getId());
            out.add(new ObrigacaoDaEmpresaDto(m.getId(), m.getNome(), e == null ? null : e.getNome(), m.getPeriodicidade(), m.getEsfera(), descreverVencimento(m),
                    v != null, v != null && v.isAtivo() && m.isAtivo(), compativel(c, m), v == null ? null : v.getResponsavelId(), v != null && v.isPersonalizado()));
        }
        return out;
    }

    /** Liga ou desliga a obrigação para a empresa e define o responsável padrão dela; cria o vínculo se ainda não existir (escolha manual). */
    @Transactional
    public ObrigacaoDaEmpresaDto atualizarVinculo(UsuarioLogado quem, Long clienteId, Long modeloId, AtualizaVinculo r) {
        Cliente c = clientes.findByIdAndTenantId(clienteId, quem.tenantId()).orElseThrow(() -> new NaoEncontradoException("Empresa"));
        ObrigacaoModelo m = modeloDoAlcance(quem, modeloId);
        ClienteObrigacao v = vinculos.findByClienteIdAndModeloIdAndTenantId(clienteId, modeloId, quem.tenantId()).orElse(null);
        if (v == null) { criarVinculo(c, m, !compativel(c, m)); v = vinculos.findByClienteIdAndModeloIdAndTenantId(clienteId, modeloId, quem.tenantId()).orElseThrow(); }
        if (r.ativa() != null) { v.setAtivo(r.ativa()); v.setPersonalizado(true); }
        if (r.limparResponsavel()) { v.setResponsavelId(null); v.setPersonalizado(true); }
        else if (r.responsavelId() != null) {
            usuarios.findByIdAndTenantId(r.responsavelId(), quem.tenantId()).filter(br.com.viriato.usuario.Usuario::isAtivo).orElseThrow(() -> new NegocioException("O responsável precisa ser um funcionário ativo"));
            v.setResponsavelId(r.responsavelId());
            v.setPersonalizado(true);
        }
        vinculos.save(v);
        Entrega e = entregas.findByIdAndTenantId(m.getEntregaId(), quem.tenantId()).orElse(null);
        return new ObrigacaoDaEmpresaDto(m.getId(), m.getNome(), e == null ? null : e.getNome(), m.getPeriodicidade(), m.getEsfera(), descreverVencimento(m), true,
                v.isAtivo() && m.isAtivo(), compativel(c, m), v.getResponsavelId(), v.isPersonalizado());
    }

    // ---------- vencimento ----------

    /** Data de vencimento da obrigação para a competência (ano, mês), já ajustada pelo calendário da empresa atendida. */
    public static LocalDate vencimento(CalendarioFiscal cal, ObrigacaoModelo m, int ano, int mes) {
        YearMonth base = YearMonth.of(ano, mes).plusMonths(m.getMesesDefasagem());
        return switch (m.getModoVencimento()) {
            case DIA_DO_MES -> cal.ajustar(base.atDay(Math.min(m.getDiaVencimento(), base.lengthOfMonth())), m.getRegraDiaUtil());
            case ENESIMO_DIA_UTIL -> cal.enesimoDiaUtil(base.getYear(), base.getMonthValue(), m.getDiaVencimento());
            case ULTIMO_DIA_UTIL -> cal.ultimoDiaUtil(base.getYear(), base.getMonthValue());
        };
    }

    public static boolean aplicaNaCompetencia(ObrigacaoModelo m, int mes) { return lista(m.getMesesAplicaveis()).contains(String.valueOf(mes)); }

    static String descreverVencimento(ObrigacaoModelo m) {
        String quando = m.getMesesDefasagem() == 0 ? "no mês" : m.getMesesDefasagem() == 1 ? "no mês seguinte" : "+" + m.getMesesDefasagem() + " meses";
        return switch (m.getModoVencimento()) {
            case DIA_DO_MES -> "dia " + m.getDiaVencimento() + " " + quando + (m.getRegraDiaUtil() == RegraDiaUtil.ANTECIPAR_DIA_UTIL ? " (antecipa)" : m.getRegraDiaUtil() == RegraDiaUtil.POSTERGAR_DIA_UTIL ? " (posterga)" : "");
            case ENESIMO_DIA_UTIL -> m.getDiaVencimento() + "º dia útil " + quando;
            case ULTIMO_DIA_UTIL -> "último dia útil " + quando;
        };
    }

    // ---------- JSON ----------

    static List<String> lista(String json) {
        if (json == null || json.isBlank()) return List.of();
        try { return JSON.readValue(json, new TypeReference<List<Object>>() {}).stream().map(String::valueOf).toList(); } catch (Exception e) { return List.of(); }
    }

    private static String escrever(Object o) { try { return JSON.writeValueAsString(o); } catch (Exception e) { throw new IllegalStateException(e); } }

    private ModeloDto dto(ObrigacaoModelo m, Entrega e, List<Long> deps) {
        List<Integer> meses = lista(m.getMesesAplicaveis()).stream().map(Integer::valueOf).toList();
        List<RegimeTributario> regimes = lista(m.getRegimesAplicaveis()).stream().map(RegimeTributario::valueOf).toList();
        long vinc = vinculos.findByModeloIdAndTenantIdAndAtivoTrue(m.getId(), m.getTenantId()).size();
        return new ModeloDto(m.getId(), m.getNome(), m.getEntregaId(), e == null ? null : e.getNome(), e == null ? null : e.getSetorDonoId(), m.getPeriodicidade(), m.getModoVencimento(),
                m.getDiaVencimento(), m.getMesesDefasagem(), meses, m.getRegraDiaUtil(), m.getEsfera(), regimes, lista(m.getUfsAplicaveis()), lista(m.getCnaesAplicaveis()),
                m.getResponsavelPadraoId(), m.isAtivo(), deps, vinc);
    }
}
