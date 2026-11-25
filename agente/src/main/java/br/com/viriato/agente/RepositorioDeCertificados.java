package br.com.viriato.agente;

import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import java.util.List;

/** De onde o agente lê os certificados (com chave privada) do computador. */
interface RepositorioDeCertificados {
    /** Um certificado com chave privada: o repositório e o apelido dele dentro do repositório. */
    record Local(String thumbprint, String alias, X509Certificate certificado) {}

    /** O repositório de chaves de onde a chave privada é usada (sem nunca ser exportada). */
    KeyStore repositorio() throws Exception;

    /** Senha da chave (nula no Windows, onde quem protege é o sistema/token). */
    char[] senhaDaChave();

    /** Certificados com chave privada, na ordem do repositório. */
    List<Local> listar() throws Exception;

    static String thumbprint(X509Certificate x) throws Exception {
        return HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-1").digest(x.getEncoded()));
    }
}
