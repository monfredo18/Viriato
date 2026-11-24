package br.com.viriato.documento;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** Envia o arquivo ao daemon ClamAV (clamd) pelo comando INSTREAM, por TCP, e lê o veredito. */
public class ClamAvScanner implements AntivirusScanner {
    private final String host;
    private final int porta;
    private final int timeoutMs;

    public ClamAvScanner(String host, int porta, int timeoutMs) {
        this.host = host;
        this.porta = porta;
        this.timeoutMs = timeoutMs;
    }

    @Override
    public String examinar(String nome, InputStream conteudo) throws IOException {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, porta), timeoutMs);
            s.setSoTimeout(timeoutMs);
            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            out.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = conteudo.read(buf)) > 0) {
                out.writeInt(n);
                out.write(buf, 0, n);
            }
            out.writeInt(0);
            out.flush();
            String resposta = new String(s.getInputStream().readAllBytes(), StandardCharsets.US_ASCII).replace("\0", "").trim();
            // "stream: OK" | "stream: Eicar-Signature FOUND" | "... ERROR"
            if (resposta.endsWith("OK")) return null;
            if (resposta.endsWith("FOUND")) {
                String corpo = resposta.startsWith("stream:") ? resposta.substring(7).trim() : resposta;
                return corpo.substring(0, corpo.length() - "FOUND".length()).trim();
            }
            throw new IOException("Resposta inesperada do ClamAV: " + resposta);
        }
    }
}
