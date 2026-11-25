package br.com.viriato.agente;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Só para teste e desenvolvimento fora do Windows: lê um arquivo .pfx local indicado por {@code VIRIATO_AGENTE_PFX} (e a senha em
 * {@code VIRIATO_AGENTE_PFX_SENHA}). Não substitui o repositório do Windows e não envia o arquivo a lugar nenhum.
 */
final class RepositorioPkcs12 implements RepositorioDeCertificados {
    private final Path arquivo;
    private final char[] senha;

    RepositorioPkcs12(Path arquivo, char[] senha) {
        this.arquivo = arquivo;
        this.senha = senha;
    }

    @Override
    public KeyStore repositorio() throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(arquivo)) { ks.load(in, senha); }
        return ks;
    }

    @Override public char[] senhaDaChave() { return senha; }

    @Override
    public List<Local> listar() throws Exception {
        KeyStore ks = repositorio();
        List<Local> r = new ArrayList<>();
        for (String alias : Collections.list(ks.aliases())) {
            if (ks.isKeyEntry(alias) && ks.getCertificate(alias) instanceof X509Certificate x) r.add(new Local(RepositorioDeCertificados.thumbprint(x), alias, x));
        }
        return r;
    }
}
