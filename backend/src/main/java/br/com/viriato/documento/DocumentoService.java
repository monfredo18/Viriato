package br.com.viriato.documento;

import br.com.viriato.comum.NaoEncontradoException;
import br.com.viriato.comum.NegocioException;
import br.com.viriato.fluxo.EtapaRequisito;
import br.com.viriato.fluxo.EtapaRequisitoRepository;
import br.com.viriato.fluxo.RequisitoService;
import br.com.viriato.fluxo.TipoRequisito;
import br.com.viriato.processo.Processo;
import br.com.viriato.processo.ProcessoService;
import br.com.viriato.processo.StatusProcesso;
import br.com.viriato.seguranca.UsuarioLogado;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DocumentoService {
    private final DocumentoRepository repo;
    private final ArmazenamentoArquivos storage;
    private final ProcessoService processos;
    private final RequisitoService requisitos;
    private final EtapaRequisitoRepository requisitosRepo;
    private final ValidadorDeArquivo validador;
    private final br.com.viriato.ged.ArvoreArquivistica arvore;
    private final br.com.viriato.ged.ValidacaoDocumento conferencia;
    private final br.com.viriato.usuario.UsuarioRepository usuarios;
    private final br.com.viriato.auditoria.AuditoriaService auditoria;
    private final AntivirusScanner antivirus;
    private final boolean falharFechado;

    public DocumentoService(DocumentoRepository repo, ArmazenamentoArquivos storage, ProcessoService processos,
                            RequisitoService requisitos, EtapaRequisitoRepository requisitosRepo,
                            ValidadorDeArquivo validador, AntivirusScanner antivirus, br.com.viriato.ged.ArvoreArquivistica arvore, br.com.viriato.ged.ValidacaoDocumento conferencia,
                            br.com.viriato.usuario.UsuarioRepository usuarios, br.com.viriato.auditoria.AuditoriaService auditoria,
                            @org.springframework.beans.factory.annotation.Value("${viriato.antivirus.falhar-fechado:true}") boolean falharFechado) {
        this.arvore = arvore;
        this.conferencia = conferencia;
        this.usuarios = usuarios;
        this.auditoria = auditoria;
        this.validador = validador;
        this.antivirus = antivirus;
        this.falharFechado = falharFechado;
        this.repo = repo;
        this.storage = storage;
        this.processos = processos;
        this.requisitos = requisitos;
        this.requisitosRepo = requisitosRepo;
    }

    /** {@code requisito}: descrição do requisito de etapa que este documento cumpre, se houver. */
    public record DocumentoDto(Long id, String nome, String contentType, long tamanho, Long enviadoPor, Instant criadoEm,
                               Long requisitoId, String requisito, boolean automatico, String categoria, boolean guardaLegal, boolean arquivado, Integer ano, Integer mes) {
        static DocumentoDto de(Documento d, String requisito) {
            return new DocumentoDto(d.getId(), d.getNomeOriginal(), d.getContentType(), d.getTamanho(),
                    d.getEnviadoPor(), d.getCriadoEm(), d.getRequisitoId(), requisito, d.isAutomatico(), d.getCategoria(), d.isGuardaLegal(), d.isArquivado(), d.getAno(), d.getMes());
        }
    }

    public record Arquivo(Documento documento, InputStream conteudo) {}

    @Transactional
    public DocumentoDto enviar(UsuarioLogado quem, Long processoId, MultipartFile arquivo, Long requisitoId) throws IOException {
        return enviar(quem, processoId, arquivo, requisitoId, false, null);
    }

    @Transactional
    public DocumentoDto enviar(UsuarioLogado quem, Long processoId, MultipartFile arquivo, Long requisitoId, boolean guardaLegal, String categoria) throws IOException {
        Processo processo = processos.buscar(quem, processoId); // garante que o processo é do tenant
        processos.exigirPodeMexer(quem, processo);
        if (arquivo == null || arquivo.isEmpty()) throw new NegocioException("Arquivo vazio");
        EtapaRequisito requisito = null;
        if (requisitoId != null) {   // anexo que cumpre um requisito da etapa atual
            if (processo.getStatus() != StatusProcesso.ABERTO) throw new NegocioException("Processo não está aberto");
            requisito = requisitos.daEtapaAtual(processo, requisitoId, TipoRequisito.DOCUMENTO);
        }

        String nome = limparNome(arquivo.getOriginalFilename());
        String tipo = validarConteudo(nome, arquivo);
        var hoje = br.com.viriato.comum.Datas.hoje();
        int ano = processo.getCompetenciaAno() != null ? processo.getCompetenciaAno() : hoje.getYear();
        int mes = processo.getCompetenciaMes() != null ? processo.getCompetenciaMes() : hoje.getMonthValue();
        String chave = arvore.chave(quem.tenantId(), processo.getClienteId(), ano, mes, processo.getSetorId(), processoId, nome);

        try (InputStream in = arquivo.getInputStream()) {
            storage.salvar(chave, in, arquivo.getSize(), tipo);
        }
        Documento d = new Documento();
        d.setTenantId(quem.tenantId());
        d.setProcessoId(processoId);
        d.setNomeOriginal(nome);
        d.setChaveS3(chave);
        d.setContentType(tipo);
        d.setTamanho(arquivo.getSize());
        d.setEnviadoPor(quem.id());
        d.setRequisitoId(requisito == null ? null : requisito.getId());
        d.setClienteId(processo.getClienteId());
        d.setSetorId(processo.getSetorId());
        d.setAno(ano);
        d.setMes(mes);
        d.setCategoria(categoria);
        d.setGuardaLegal(guardaLegal);
        try {
            return DocumentoDto.de(repo.save(d), requisito == null ? null : requisito.getDescricao());
        } catch (RuntimeException e) {
            storage.remover(chave); // não deixa arquivo órfão se o banco falhar
            throw e;
        }
    }

    /** Confere extensão e conteúdo (magic bytes) e passa o arquivo pelo antivírus; devolve o tipo a gravar. Reaproveitável por outros módulos. */
    public String validarConteudo(String nome, MultipartFile arquivo) throws IOException {
        byte[] inicio;
        try (InputStream in = arquivo.getInputStream()) { inicio = in.readNBytes(ValidadorDeArquivo.CABECALHO); }
        String tipo = validador.validar(nome, inicio);
        String ameaca;
        try (InputStream in = arquivo.getInputStream()) {
            ameaca = antivirus.examinar(nome, in);
        } catch (IOException e) {
            if (falharFechado) throw new NegocioException("Não foi possível verificar o arquivo com o antivírus agora. Tente novamente em instantes.");
            ameaca = null;
        }
        if (ameaca != null) throw new NegocioException("Arquivo recusado: o antivírus encontrou uma ameaça (" + ameaca + ").");
        return tipo;
    }

    /** Mesma conferência (extensão, conteúdo e antivírus) para bytes que não vieram de um upload direto, como as notas dentro de um .zip. */
    public String validarBytes(String nome, byte[] bytes) {
        String tipo = validador.validar(nome, java.util.Arrays.copyOf(bytes, Math.min(bytes.length, ValidadorDeArquivo.CABECALHO)));
        String ameaca;
        try {
            ameaca = antivirus.examinar(nome, new java.io.ByteArrayInputStream(bytes));
        } catch (IOException e) {
            if (falharFechado) throw new NegocioException("Não foi possível verificar o arquivo com o antivírus agora. Tente novamente em instantes.");
            ameaca = null;
        }
        if (ameaca != null) throw new NegocioException("Arquivo recusado: o antivírus encontrou uma ameaça (" + ameaca + ").");
        return tipo;
    }

    @Transactional(readOnly = true)
    public List<DocumentoDto> listar(UsuarioLogado quem, Long processoId) {
        processos.buscar(quem, processoId);   // também garante que o processo é do setor de quem pede
        List<Documento> docs = repo.findByProcessoIdAndTenantIdOrderByIdDesc(processoId, quem.tenantId());
        Map<Long, String> rotulos = requisitosRepo.findAllById(docs.stream().map(Documento::getRequisitoId).filter(Objects::nonNull).toList())
                .stream().collect(Collectors.toMap(EtapaRequisito::getId, EtapaRequisito::getDescricao));
        return docs.stream().map(d -> DocumentoDto.de(d, d.getRequisitoId() == null ? null : rotulos.get(d.getRequisitoId()))).toList();
    }

    @Transactional(readOnly = true)
    public Arquivo baixar(UsuarioLogado quem, Long id) {
        Documento d = repo.findByIdAndTenantId(id, quem.tenantId()).orElseThrow(() -> new NaoEncontradoException("Documento"));
        exigirAcesso(quem, d, false);
        return new Arquivo(d, storage.abrir(d.getChaveS3()));
    }

    @Transactional
    public void remover(UsuarioLogado quem, Long id) {
        Documento d = repo.findByIdAndTenantId(id, quem.tenantId()).orElseThrow(() -> new NaoEncontradoException("Documento"));
        exigirAcesso(quem, d, true);
        if (d.isGuardaLegal()) {
            boolean venceuRetencao = d.getCriadoEm() != null && d.getCriadoEm().isBefore(java.time.Instant.now().minus(java.time.Duration.ofDays(5 * 365L)));
            if (quem.papel() != br.com.viriato.usuario.Papel.ADMIN || !venceuRetencao) {
                throw new NegocioException("Este documento está em guarda legal (mínimo de 5 anos) e não pode ser excluído. Arquive-o: ele sai da lista, mas fica guardado e o arquivamento é registrado na auditoria.");
            }
        }
        auditoria.registrar(quem.tenantId(), quem.id(), "DOCUMENTO_EXCLUIDO", d.getNomeOriginal() + (d.isGuardaLegal() ? " (guarda legal vencida)" : ""));
        repo.delete(d);
        storage.remover(d.getChaveS3());
    }

    /**
     * Confere o arquivo ANTES de gravar: extensão, conteúdo e antivírus (recusa com 422) e, se o processo tem empresa e competência,
     * o CNPJ e o período citados no documento (avisos, não bloqueiam). Nada é gravado.
     */
    @Transactional(readOnly = true)
    public List<br.com.viriato.ged.ValidacaoDocumento.Alerta> verificar(UsuarioLogado quem, Long processoId, MultipartFile arquivo) throws IOException {
        Processo processo = processos.buscar(quem, processoId);
        processos.exigirPodeMexer(quem, processo);
        if (arquivo == null || arquivo.isEmpty()) throw new NegocioException("Arquivo vazio");
        String nome = limparNome(arquivo.getOriginalFilename());
        validarConteudo(nome, arquivo);
        return conferencia.analisar(nome, arquivo.getBytes(), quem.tenantId(), processo.getClienteId(), processo.getCompetenciaAno(), processo.getCompetenciaMes());
    }

    /** Arquiva: mantém o arquivo guardado, marca o documento e registra quem arquivou. Serve a qualquer documento, o único caminho dos de guarda legal. */
    @Transactional
    public DocumentoDto arquivar(UsuarioLogado quem, Long id, boolean arquivar) {
        Documento d = repo.findByIdAndTenantId(id, quem.tenantId()).orElseThrow(() -> new NaoEncontradoException("Documento"));
        exigirAcesso(quem, d, true);
        d.setArquivado(arquivar);
        d.setArquivadoEm(arquivar ? java.time.Instant.now() : null);
        d.setArquivadoPor(arquivar ? quem.id() : null);
        repo.save(d);
        auditoria.registrar(quem.tenantId(), quem.id(), arquivar ? "DOCUMENTO_ARQUIVADO" : "DOCUMENTO_DESARQUIVADO", d.getNomeOriginal());
        return DocumentoDto.de(d, null);
    }

    /** Documento de processo segue as regras do processo; documento só do GED (sem processo) é de administrador e gestor (este, só do próprio setor). */
    public void exigirAcesso(UsuarioLogado quem, Documento d, boolean alterar) {
        if (d.getProcessoId() != null) {
            Processo p = processos.buscar(quem, d.getProcessoId());
            if (alterar) processos.exigirPodeMexer(quem, p);
            return;
        }
        if (quem.papel() == br.com.viriato.usuario.Papel.USUARIO) throw new NaoEncontradoException("Documento");
        if (quem.papel() == br.com.viriato.usuario.Papel.GESTOR && d.getSetorId() != null && !d.getSetorId().equals(usuarios.setorDe(quem))) throw new NaoEncontradoException("Documento");
    }

    /** Remove caminhos e caracteres perigosos do nome enviado pelo cliente. */
    static String limparNome(String original) {
        String n = original == null ? "arquivo" : original.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1).replaceAll("[^\\p{L}\\p{N}._ -]", "_").trim();
        if (n.isEmpty() || n.equals(".") || n.equals("..")) n = "arquivo";
        return n.length() > 200 ? n.substring(n.length() - 200) : n;
    }
}
