package br.com.viriato.usuario;

import br.com.viriato.comum.NaoEncontradoException;
import br.com.viriato.comum.NegocioException;
import br.com.viriato.documento.ArmazenamentoArquivos;
import br.com.viriato.seguranca.UsuarioLogado;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Foto de perfil. O tipo da imagem vem do CONTEÚDO do arquivo (JPEG, PNG ou WebP), nunca do nome ou do tipo
 * informado pelo navegador, e o arquivo é guardado com um nome gerado pelo sistema. O tamanho é limitado: o
 * navegador já reduz a imagem antes de enviar, o limite aqui protege contra envio direto pela API.
 */
@Service
public class FotoService {
    static final long MAX_BYTES = 2L * 1024 * 1024;

    public record Foto(String tipo, InputStream conteudo) {}

    private final UsuarioRepository usuarios;
    private final ArmazenamentoArquivos storage;

    public FotoService(UsuarioRepository usuarios, ArmazenamentoArquivos storage) {
        this.usuarios = usuarios;
        this.storage = storage;
    }

    /** Tipo e extensão pelos primeiros bytes; {@code null} se não for uma imagem aceita. */
    static String[] tipoPeloConteudo(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return new String[]{"image/jpeg", "jpg"};
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G' && b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A)
            return new String[]{"image/png", "png"};
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P')
            return new String[]{"image/webp", "webp"};
        return null;
    }

    private Usuario daEmpresa(Long id, Long tenantId) {
        return usuarios.findByIdAndTenantId(id, tenantId).orElseThrow(() -> new NaoEncontradoException("Usuário"));
    }

    @Transactional
    public UsuarioDto trocar(UsuarioLogado quem, MultipartFile arquivo) throws IOException {
        if (arquivo == null || arquivo.isEmpty()) throw new NegocioException("Escolha uma imagem");
        if (arquivo.getSize() > MAX_BYTES) throw new NegocioException("A foto pode ter no máximo 2 MB");
        byte[] bytes = arquivo.getBytes();
        String[] tipo = tipoPeloConteudo(bytes);
        if (tipo == null) throw new NegocioException("Use uma imagem JPEG, PNG ou WebP");

        Usuario u = daEmpresa(quem.id(), quem.tenantId());
        String antiga = u.getFotoChave();
        String chave = "tenant-%d/perfil-%d/%s.%s".formatted(quem.tenantId(), u.getId(), UUID.randomUUID(), tipo[1]);
        storage.salvar(chave, new ByteArrayInputStream(bytes), bytes.length, tipo[0]);
        u.setFotoChave(chave);
        u.setFotoTipo(tipo[0]);
        u.setFotoVersao(System.currentTimeMillis());
        UsuarioDto dto = UsuarioDto.de(usuarios.save(u));
        apagarArquivo(antiga);
        return dto;
    }

    @Transactional
    public void remover(Long usuarioId, Long tenantId) {
        Usuario u = daEmpresa(usuarioId, tenantId);
        String antiga = u.getFotoChave();
        u.setFotoChave(null);
        u.setFotoTipo(null);
        u.setFotoVersao(null);
        usuarios.save(u);
        apagarArquivo(antiga);
    }

    /** Ao apagar a pessoa: só o arquivo (o registro some junto com o usuário). */
    public void apagarArquivoDe(Usuario u) { apagarArquivo(u.getFotoChave()); }

    @Transactional(readOnly = true)
    public Foto abrir(Long usuarioId, Long tenantId) {
        Usuario u = daEmpresa(usuarioId, tenantId);
        if (u.getFotoChave() == null) throw new NaoEncontradoException("Foto");
        return new Foto(u.getFotoTipo(), storage.abrir(u.getFotoChave()));
    }

    private void apagarArquivo(String chave) {
        if (chave == null) return;
        try {
            storage.remover(chave);
        } catch (RuntimeException e) {
            // arquivo órfão não atrapalha ninguém; o importante é a foto nova valer
        }
    }
}
