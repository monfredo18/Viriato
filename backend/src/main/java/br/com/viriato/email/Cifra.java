package br.com.viriato.email;

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
 * Criptografa segredos guardados no banco (como a senha do servidor de e-mail): AES-256-GCM com IV aleatório.
 * A chave vem de {@code CHAVE_CIFRA} (ou, na falta dela, do {@code JWT_SECRET}); se a chave mudar, os segredos já
 * guardados não podem mais ser lidos e precisam ser informados de novo.
 */
@Component
public class Cifra {
    private static final SecureRandom ALEATORIO = new SecureRandom();
    private final SecretKeySpec chave;

    public Cifra(@Value("${viriato.cripto.chave}") String segredo) {
        try {
            this.chave = new SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(("viriato-cifra:" + segredo).getBytes(StandardCharsets.UTF_8)), "AES");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public String cifrar(String texto) {
        try {
            byte[] iv = new byte[12];
            ALEATORIO.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, chave, new GCMParameterSpec(128, iv));
            byte[] cifrado = c.doFinal(texto.getBytes(StandardCharsets.UTF_8));
            byte[] tudo = new byte[iv.length + cifrado.length];
            System.arraycopy(iv, 0, tudo, 0, iv.length);
            System.arraycopy(cifrado, 0, tudo, iv.length, cifrado.length);
            return Base64.getEncoder().encodeToString(tudo);
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao criptografar", e);
        }
    }

    /** @return o texto original, ou {@code null} se não puder ser lido (chave diferente ou dado corrompido) */
    public String decifrar(String valor) {
        try {
            byte[] tudo = Base64.getDecoder().decode(valor);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, chave, new GCMParameterSpec(128, tudo, 0, 12));
            return new String(c.doFinal(tudo, 12, tudo.length - 12), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
