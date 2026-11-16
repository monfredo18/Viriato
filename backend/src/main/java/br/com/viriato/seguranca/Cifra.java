package br.com.viriato.seguranca;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Cifra segredos guardados no banco (hoje, o da verificação em duas etapas) com AES-256-GCM. A chave deriva do
 * {@code JWT_SECRET}: trocar o segredo invalida os segredos já cifrados (as pessoas precisam reativar a verificação).
 */
@Component
public class Cifra {
    private final SecretKeySpec chave;
    private final SecureRandom azar = new SecureRandom();

    public Cifra(@Value("${viriato.jwt.secret}") String segredo) throws Exception {
        this.chave = new SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(("viriato-cifra|" + segredo).getBytes(StandardCharsets.UTF_8)), "AES");
    }

    public String cifrar(String texto) {
        try {
            byte[] iv = new byte[12];
            azar.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, chave, new GCMParameterSpec(128, iv));
            byte[] dados = c.doFinal(texto.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + dados.length).put(iv).put(dados).array());
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao cifrar", e);
        }
    }

    /** {@code null} se o texto não puder ser aberto com a chave atual. */
    public String abrir(String cifrado) {
        try {
            byte[] tudo = Base64.getDecoder().decode(cifrado);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, chave, new GCMParameterSpec(128, tudo, 0, 12));
            return new String(c.doFinal(tudo, 12, tudo.length - 12), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
