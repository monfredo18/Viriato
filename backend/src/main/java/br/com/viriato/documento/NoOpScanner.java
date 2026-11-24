package br.com.viriato.documento;

import java.io.InputStream;

/** Não examina nada (desenvolvimento e testes). Em produção, ative o ClamAV com {@code VIRIATO_CLAMAV_HABILITADO=true}. */
public class NoOpScanner implements AntivirusScanner {
    @Override
    public String examinar(String nome, InputStream conteudo) { return null; }
}
