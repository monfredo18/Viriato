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

    public DocumentoService(DocumentoRepository repo, ArmazenamentoArquivos storage, ProcessoService processos,
                            RequisitoService requisitos, EtapaRequisitoRepository requisitosRepo) {
        this.repo = repo;
        this.storage = storage;
        this.processos = processos;
        this.requisitos = requisitos;
        this.requisitosRepo = requisitosRepo;
    }

    /** {@code requisito}: descrição do requisito de etapa que este documento cumpre, se houver. */
    public record DocumentoDto(Long id, String nome, String contentType, long tamanho, Long enviadoPor, Instant criadoEm,
                               Long requisitoId, String requisito) {
        static DocumentoDto de(Documento d, String requisito) {
            return new DocumentoDto(d.getId(), d.getNomeOriginal(), d.getContentType(), d.getTamanho(),
                    d.getEnviadoPor(), d.getCriadoEm(), d.getRequisitoId(), requisito);
        }
    }

    public record Arquivo(Documento documento, InputStream conteudo) {}

    @Transactional
    public DocumentoDto enviar(UsuarioLogado quem, Long processoId, MultipartFile arquivo, Long requisitoId) throws IOException {
        Processo processo = processos.buscar(quem, processoId); // garante que o processo é do tenant
        processos.exigirPodeMexer(quem, processo);
        if (arquivo == null || arquivo.isEmpty()) throw new NegocioException("Arquivo vazio");
        EtapaRequisito requisito = null;
        if (requisitoId != null) {   // anexo que cumpre um requisito da etapa atual
            if (processo.getStatus() != StatusProcesso.ABERTO) throw new NegocioException("Processo não está aberto");
            requisito = requisitos.daEtapaAtual(processo, requisitoId, TipoRequisito.DOCUMENTO);
        }

        String nome = limparNome(arquivo.getOriginalFilename());
        String chave = "tenant-%d/processo-%d/%s-%s".formatted(quem.tenantId(), processoId, UUID.randomUUID(), nome);
        String tipo = arquivo.getContentType() != null ? arquivo.getContentType() : "application/octet-stream";

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
        try {
            return DocumentoDto.de(repo.save(d), requisito == null ? null : requisito.getDescricao());
        } catch (RuntimeException e) {
            storage.remover(chave); // não deixa arquivo órfão se o banco falhar
            throw e;
        }
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
        processos.buscar(quem, d.getProcessoId());
        return new Arquivo(d, storage.abrir(d.getChaveS3()));
    }

    @Transactional
    public void remover(UsuarioLogado quem, Long id) {
        Documento d = repo.findByIdAndTenantId(id, quem.tenantId()).orElseThrow(() -> new NaoEncontradoException("Documento"));
        processos.exigirPodeMexer(quem, processos.buscar(quem, d.getProcessoId()));
        repo.delete(d);
        storage.remover(d.getChaveS3());
    }

    /** Remove caminhos e caracteres perigosos do nome enviado pelo cliente. */
    static String limparNome(String original) {
        String n = original == null ? "arquivo" : original.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1).replaceAll("[^\\p{L}\\p{N}._ -]", "_").trim();
        if (n.isEmpty() || n.equals(".") || n.equals("..")) n = "arquivo";
        return n.length() > 200 ? n.substring(n.length() - 200) : n;
    }
}
