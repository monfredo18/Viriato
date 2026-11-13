package br.com.viriato;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.viriato.documento.ArmazenamentoLocal;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Armazenamento em disco (desenvolvimento): nomes com acento e chaves maliciosas. */
class ArmazenamentoLocalTest {

    @Test
    void guardaLeERemoveArquivoComAcentoNoNome(@TempDir Path pasta) throws Exception {
        var a = new ArmazenamentoLocal(pasta.toString());
        String chave = "tenant-1/processo-2/abc-Ficha-42-Análise ção.pdf";
        a.salvar(chave, new ByteArrayInputStream("conteudo".getBytes(StandardCharsets.UTF_8)), 8, "application/pdf");
        assertThat(new String(a.abrir(chave).readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("conteudo");
        try (var arquivos = Files.walk(pasta)) {
            assertThat(arquivos.filter(Files::isRegularFile).map(p -> p.getFileName().toString()).toList())
                    .allMatch(n -> n.matches("[A-Za-z0-9._-]+"));   // só caracteres seguros no disco
        }
        a.remover(chave);
        assertThatThrownBy(() -> a.abrir(chave)).isInstanceOf(java.io.UncheckedIOException.class);
    }

    @Test
    void nuncaSaiDaPastaBase(@TempDir Path pasta) {
        var a = new ArmazenamentoLocal(pasta.toString());
        assertThatThrownBy(() -> a.salvar("../fora.txt", new ByteArrayInputStream(new byte[]{1}), 1, "text/plain")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> a.abrir("tenant-1/../../etc/passwd")).isInstanceOf(IllegalArgumentException.class);
    }
}
