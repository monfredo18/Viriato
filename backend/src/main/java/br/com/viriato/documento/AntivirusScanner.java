package br.com.viriato.documento;

import java.io.IOException;
import java.io.InputStream;

/** Examina o conteúdo de um arquivo recebido antes de ele ser guardado. Implementações: {@link NoOpScanner} e {@link ClamAvScanner}. */
public interface AntivirusScanner {
    /** @return {@code null} se o arquivo está limpo; senão, o nome da ameaça encontrada. */
    String examinar(String nome, InputStream conteudo) throws IOException;
}
