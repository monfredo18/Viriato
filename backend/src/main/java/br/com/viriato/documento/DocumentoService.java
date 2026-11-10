package br.com.viriato.documento;

import br.com.viriato.comum.NaoEncontradoException;
import br.com.viriato.comum.NegocioException;
import br.com.viriato.processo.ProcessoService;
import br.com.viriato.seguranca.UsuarioLogado;
import br.com.viriato.usuario.Papel;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DocumentoService {
    private final DocumentoRepository repo;
    private final ArmazenamentoArquivos storage;
    private final ProcessoService processos;

    public DocumentoService(DocumentoRepository repo, ArmazenamentoArquivos storage, ProcessoService processos) {
        this.repo = repo;
        this.storage = storage;
        this.processos = processos;
    }

    public record DocumentoDto(Long id, String nome, String contentType, long tamanho, Long enviadoPor, Instant criadoEm) {
        static DocumentoDto de(Documento d) {
            return new DocumentoDto(d.getId(), d.getNomeOriginal(), d.getContentType(), d.getTamanho(),
                    d.getEnviadoPor(), d.getCriadoEm());
        }
    }

    public record Arquivo(Documento documento, InputStream conteudo) {}

    @Transactional
    public DocumentoDto enviar(UsuarioLogado quem, Long processoId, MultipartFile arquivo) throws IOException {
        processos.buscar(processoId, quem.tenantId()); // garante que o processo é do tenant
        if (arquivo == null || arquivo.isEmpty()) throw new NegocioException("Arquivo vazio");

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
        try {
            return DocumentoDto.de(repo.save(d));
        } catch (RuntimeException e) {
            storage.remover(chave); // não deixa arquivo órfão se o banco falhar
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public List<DocumentoDto> listar(Long processoId, Long tenantId) {
        processos.buscar(processoId, tenantId);
        return repo.findByProcessoIdAndTenantIdOrderByIdDesc(processoId, tenantId).stream().map(DocumentoDto::de).toList();
    }

    @Transactional(readOnly = true)
    public Arquivo baixar(Long id, Long tenantId) {
        Documento d = repo.findByIdAndTenantId(id, tenantId).orElseThrow(() -> new NaoEncontradoException("Documento"));
        return new Arquivo(d, storage.abrir(d.getChaveS3()));
    }

    @Transactional
    public void remover(UsuarioLogado quem, Long id) {
        Documento d = repo.findByIdAndTenantId(id, quem.tenantId()).orElseThrow(() -> new NaoEncontradoException("Documento"));
        if (quem.papel() == Papel.USUARIO && !quem.id().equals(d.getEnviadoPor())) {
            throw new NegocioException("Apenas quem enviou ou um gestor pode remover o documento");
        }
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
