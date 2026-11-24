package br.com.viriato.ged;

import br.com.viriato.auditoria.AuditoriaService;
import br.com.viriato.cadastro.ClienteRepository;
import br.com.viriato.cadastro.Setor;
import br.com.viriato.cadastro.SetorRepository;
import br.com.viriato.comum.Datas;
import br.com.viriato.comum.NaoEncontradoException;
import br.com.viriato.comum.NegocioException;
import br.com.viriato.documento.ArmazenamentoArquivos;
import br.com.viriato.documento.Documento;
import br.com.viriato.documento.DocumentoRepository;
import br.com.viriato.documento.DocumentoService;
import br.com.viriato.seguranca.UsuarioLogado;
import br.com.viriato.usuario.Papel;
import br.com.viriato.usuario.UsuarioRepository;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * GED contábil: os documentos de uma empresa atendida, organizados em Ano > Mês > Setor (a mesma árvore do armazenamento).
 * Reúne os anexos dos processos e os arquivos enviados direto à pasta da empresa. Administrador vê todos os setores; gestor,
 * só os do próprio setor e os sem setor.
 */
@Service
public class GedService {
    static final Set<String> CATEGORIAS = Set.of("GUIA", "RECIBO", "EXTRATO", "XML", "CONTRATO", "FICHA", "OUTRO");
    /** Categorias fiscais e comprovantes: nascem em guarda legal (a pessoa pode desmarcar ao enviar). */
    static final Set<String> COM_GUARDA = Set.of("GUIA", "RECIBO", "XML", "EXTRATO");

    public record PastaDto(Integer ano, Integer mes, Long setorId, String setorNome, long quantidade, long bytes) {}
    public record GedDocumentoDto(Long id, String nome, String categoria, long tamanho, Integer ano, Integer mes, Long setorId, String setorNome, Long processoId,
                                  boolean guardaLegal, boolean arquivado, Instant criadoEm, boolean automatico) {}

    private final DocumentoRepository documentos;
    private final DocumentoService documentoService;
    private final ClienteRepository clientes;
    private final SetorRepository setores;
    private final UsuarioRepository usuarios;
    private final ArmazenamentoArquivos storage;
    private final ArvoreArquivistica arvore;
    private final AuditoriaService auditoria;

    public GedService(DocumentoRepository documentos, DocumentoService documentoService, ClienteRepository clientes, SetorRepository setores, UsuarioRepository usuarios,
                      ArmazenamentoArquivos storage, ArvoreArquivistica arvore, AuditoriaService auditoria) {
        this.documentos = documentos;
        this.documentoService = documentoService;
        this.clientes = clientes;
        this.setores = setores;
        this.usuarios = usuarios;
        this.storage = storage;
        this.arvore = arvore;
        this.auditoria = auditoria;
    }

    private void empresa(UsuarioLogado quem, Long clienteId) { clientes.findByIdAndTenantId(clienteId, quem.tenantId()).orElseThrow(() -> new NaoEncontradoException("Empresa")); }

    private List<Documento> doAlcance(UsuarioLogado quem, Long clienteId) {
        empresa(quem, clienteId);
        Long meu = quem.papel() == Papel.ADMIN ? null : usuarios.setorDe(quem);
        return documentos.findByClienteIdAndTenantIdOrderByAnoDescMesDescIdDesc(clienteId, quem.tenantId()).stream()
                .filter(d -> quem.papel() == Papel.ADMIN || d.getSetorId() == null || d.getSetorId().equals(meu)).toList();
    }

    /** Pastas (ano, mês, setor) com a quantidade e o tamanho dos documentos (sem os arquivados). */
    @Transactional(readOnly = true)
    public List<PastaDto> pastas(UsuarioLogado quem, Long clienteId) {
        Map<Long, String> nomes = nomesDosSetores(quem.tenantId());
        record Chave(Integer ano, Integer mes, Long setor) {}
        Map<Chave, long[]> g = new TreeMap<>(Comparator.comparing((Chave c) -> c.ano() == null ? 0 : c.ano()).reversed()
                .thenComparing(Comparator.comparing((Chave c) -> c.mes() == null ? 0 : c.mes()).reversed()).thenComparing(c -> c.setor() == null ? -1L : c.setor()));
        for (Documento d : doAlcance(quem, clienteId)) {
            if (d.isArquivado()) continue;
            long[] v = g.computeIfAbsent(new Chave(d.getAno(), d.getMes(), d.getSetorId()), k -> new long[2]);
            v[0]++;
            v[1] += d.getTamanho();
        }
        return g.entrySet().stream().map(e -> new PastaDto(e.getKey().ano(), e.getKey().mes(), e.getKey().setor(), e.getKey().setor() == null ? "Sem setor" : nomes.getOrDefault(e.getKey().setor(), "—"), e.getValue()[0], e.getValue()[1])).toList();
    }

    @Transactional(readOnly = true)
    public List<GedDocumentoDto> listar(UsuarioLogado quem, Long clienteId, Integer ano, Integer mes, Long setorId, String categoria, String busca, boolean incluirArquivados) {
        Map<Long, String> nomes = nomesDosSetores(quem.tenantId());
        String b = busca == null ? null : busca.trim().toLowerCase();
        return doAlcance(quem, clienteId).stream()
                .filter(d -> incluirArquivados || !d.isArquivado())
                .filter(d -> ano == null || ano.equals(d.getAno()))
                .filter(d -> mes == null || mes.equals(d.getMes()))
                .filter(d -> setorId == null || setorId.equals(d.getSetorId()))
                .filter(d -> categoria == null || categoria.isBlank() || categoria.equalsIgnoreCase(d.getCategoria()))
                .filter(d -> b == null || b.isEmpty() || d.getNomeOriginal().toLowerCase().contains(b))
                .limit(500)
                .map(d -> new GedDocumentoDto(d.getId(), d.getNomeOriginal(), d.getCategoria(), d.getTamanho(), d.getAno(), d.getMes(), d.getSetorId(),
                        d.getSetorId() == null ? null : nomes.get(d.getSetorId()), d.getProcessoId(), d.isGuardaLegal(), d.isArquivado(), d.getCriadoEm(), d.isAutomatico())).toList();
    }

    /** Envia um arquivo direto para a pasta da empresa (extensão e conteúdo conferidos, antivírus, árvore arquivística). */
    @Transactional
    public GedDocumentoDto enviar(UsuarioLogado quem, Long clienteId, MultipartFile arquivo, String categoria, Integer ano, Integer mes, Long setorId, Boolean guardaLegal) throws IOException {
        empresa(quem, clienteId);
        if (arquivo == null || arquivo.isEmpty()) throw new NegocioException("Arquivo vazio");
        String cat = categoria == null || categoria.isBlank() ? "OUTRO" : categoria.trim().toUpperCase();
        if (!CATEGORIAS.contains(cat)) throw new NegocioException("Categoria inválida. Use: " + String.join(", ", new java.util.TreeSet<>(CATEGORIAS)));
        var hoje = Datas.hoje();
        int a = ano == null ? hoje.getYear() : ano, m = mes == null ? hoje.getMonthValue() : mes;
        if (a < 2000 || a > 2100 || m < 1 || m > 12) throw new NegocioException("Período inválido");
        Long setor = setorId;
        if (quem.papel() == Papel.GESTOR) {
            Long meu = usuarios.setorDe(quem);
            if (setor != null && !setor.equals(meu)) throw new NaoEncontradoException("Setor");
            if (setor == null) setor = meu;
        }
        if (setor != null) setores.findByIdAndTenantId(setor, quem.tenantId()).orElseThrow(() -> new NaoEncontradoException("Setor"));
        String nome = limpar(arquivo.getOriginalFilename());
        String tipo = documentoService.validarConteudo(nome, arquivo);
        String chave = arvore.chave(quem.tenantId(), clienteId, a, m, setor, null, nome);
        try (InputStream in = arquivo.getInputStream()) { storage.salvar(chave, in, arquivo.getSize(), tipo); }
        Documento d = novo(quem.tenantId(), clienteId, setor, a, m, cat, guardaLegal != null ? guardaLegal : COM_GUARDA.contains(cat), nome, chave, tipo, arquivo.getSize(), quem.id());
        try {
            d = documentos.save(d);
        } catch (RuntimeException e) {
            storage.remover(chave);
            throw e;
        }
        auditoria.registrar(quem.tenantId(), quem.id(), "DOCUMENTO_ENVIADO_GED", nome + " (" + cat + ", " + "%02d/%d".formatted(m, a) + ")");
        return dto(d);
    }

    /** Também usado pela importação de XMLs e pela consulta à SEFAZ. */
    public Documento novo(Long tenantId, Long clienteId, Long setorId, int ano, int mes, String categoria, boolean guarda, String nome, String chave, String tipo, long tamanho, Long enviadoPor) {
        Documento d = new Documento();
        d.setTenantId(tenantId);
        d.setClienteId(clienteId);
        d.setSetorId(setorId);
        d.setAno(ano);
        d.setMes(mes);
        d.setCategoria(categoria);
        d.setGuardaLegal(guarda);
        d.setNomeOriginal(nome);
        d.setChaveS3(chave);
        d.setContentType(tipo);
        d.setTamanho(tamanho);
        d.setEnviadoPor(enviadoPor);
        return d;
    }

    private GedDocumentoDto dto(Documento d) {
        return new GedDocumentoDto(d.getId(), d.getNomeOriginal(), d.getCategoria(), d.getTamanho(), d.getAno(), d.getMes(), d.getSetorId(),
                d.getSetorId() == null ? null : nomesDosSetores(d.getTenantId()).get(d.getSetorId()), d.getProcessoId(), d.isGuardaLegal(), d.isArquivado(), d.getCriadoEm(), d.isAutomatico());
    }

    private Map<Long, String> nomesDosSetores(Long tenantId) {
        return setores.findByTenantIdOrderByNome(tenantId).stream().collect(Collectors.toMap(Setor::getId, Setor::getNome));
    }

    static String limpar(String original) {
        String n = original == null ? "arquivo" : original.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1).replaceAll("[^\\p{L}\\p{N}._ -]", "_").trim();
        if (n.isEmpty() || n.equals(".") || n.equals("..")) n = "arquivo";
        return n.length() > 200 ? n.substring(n.length() - 200) : n;
    }
}
