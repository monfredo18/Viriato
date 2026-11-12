package br.com.viriato.documento;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Armazenamento em disco, só para desenvolvimento sem S3 ({@code ARMAZENAMENTO=local}). Em produção use o S3.
 * As chaves são geradas pelo próprio sistema, mas mesmo assim nunca saem da pasta base.
 */
@Component
@ConditionalOnProperty(name = "viriato.armazenamento.tipo", havingValue = "local")
public class ArmazenamentoLocal implements ArmazenamentoArquivos {
    private final Path base;

    public ArmazenamentoLocal(@Value("${viriato.armazenamento.pasta:./dados-arquivos}") String pasta) {
        this.base = Path.of(pasta).toAbsolutePath().normalize();
    }

    private Path resolver(String chave) {
        Path p = base.resolve(chave).normalize();
        if (!p.startsWith(base)) throw new IllegalArgumentException("Chave inválida");
        return p;
    }

    @Override
    public void salvar(String chave, InputStream conteudo, long tamanho, String contentType) {
        try {
            Path destino = resolver(chave);
            Files.createDirectories(destino.getParent());
            Files.copy(conteudo, destino, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public InputStream abrir(String chave) {
        try {
            return Files.newInputStream(resolver(chave));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void remover(String chave) {
        try {
            Files.deleteIfExists(resolver(chave));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
