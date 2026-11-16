package br.com.viriato.seguranca;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Códigos de uso único por tempo (RFC 6238: HMAC-SHA1, 6 dígitos, período de 30 s), como nos aplicativos autenticadores. */
public final class Totp {
    private static final String ALFABETO = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    static final int PERIODO = 30;

    private Totp() {}

    /** Segredo novo de 160 bits em Base32. */
    public static String novoSegredo() {
        byte[] b = new byte[20];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        int buffer = 0, bits = 0;
        for (byte x : b) {
            buffer = (buffer << 8) | (x & 0xFF);
            bits += 8;
            while (bits >= 5) { sb.append(ALFABETO.charAt((buffer >> (bits - 5)) & 31)); bits -= 5; }
        }
        return sb.toString();
    }

    private static byte[] decodificar(String s) {
        ByteBuffer out = ByteBuffer.allocate(s.length() * 5 / 8 + 1);
        int buffer = 0, bits = 0;
        for (char c : s.toCharArray()) {
            buffer = (buffer << 5) | ALFABETO.indexOf(c);
            bits += 5;
            if (bits >= 8) { out.put((byte) ((buffer >> (bits - 8)) & 0xFF)); bits -= 8; }
        }
        return java.util.Arrays.copyOf(out.array(), out.position());
    }

    public static String codigo(String segredo, long passo) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(decodificar(segredo), "HmacSHA1"));
            byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(passo).array());
            int o = h[h.length - 1] & 0x0F;
            int bin = ((h[o] & 0x7F) << 24) | ((h[o + 1] & 0xFF) << 16) | ((h[o + 2] & 0xFF) << 8) | (h[o + 3] & 0xFF);
            return "%06d".formatted(bin % 1_000_000);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static long passoAtual() { return System.currentTimeMillis() / 1000 / PERIODO; }

    /**
     * Confere o código aceitando o período anterior e o seguinte (relógios um pouco fora de hora). Devolve o período
     * em que bateu, ou {@code -1}. {@code ultimoPasso}: períodos até ele já foram usados e não valem de novo.
     */
    public static long verificar(String segredo, String digitado, Long ultimoPasso) {
        if (digitado == null) return -1;
        String d = digitado.replaceAll("\\s", "");
        if (!d.matches("\\d{6}")) return -1;
        long agora = passoAtual();
        for (long p = agora - 1; p <= agora + 1; p++) {
            boolean bate = MessageDigest.isEqual(codigo(segredo, p).getBytes(), d.getBytes());
            if (bate && (ultimoPasso == null || p > ultimoPasso)) return p;
        }
        return -1;
    }
}
