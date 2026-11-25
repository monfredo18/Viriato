package br.com.viriato.agente;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManagerFactory;

/** Apoio dos testes: certificados de verdade (keytool) e um servidor HTTPS que exige certificado do cliente, como a SEFAZ. */
final class Certs {
    static final char[] SENHA = "senha-teste".toCharArray();

    private Certs() {}

    static void keytool(String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "keytool").toString()));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String saida = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) throw new IllegalStateException("keytool falhou: " + saida);
    }

    /** Um .pfx com um certificado por CN (o repositório do "computador" do teste). */
    static Path repositorioDoComputador(Path dir, String... cns) throws Exception {
        Path arq = dir.resolve("computador.p12");
        for (String cn : cns) {
            keytool("-genkeypair", "-alias", cn.toLowerCase(), "-keyalg", "RSA", "-keysize", "2048", "-validity", "100", "-dname", "CN=" + cn + ", O=Teste, C=BR",
                    "-keystore", arq.toString(), "-storetype", "PKCS12", "-storepass", new String(SENHA), "-keypass", new String(SENHA));
        }
        return arq;
    }

    static KeyStore abrir(Path arq) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(arq)) { ks.load(in, SENHA); }
        return ks;
    }

    /** Servidor HTTPS em 127.0.0.1 que exige certificado do cliente e responde "OK:" + CN do certificado apresentado. */
    static final class ServidorMtls implements AutoCloseable {
        final HttpsServer servidor;
        final int porta;
        final KeyStore confiancaDoAgente;
        final List<String> apresentados = new CopyOnWriteArrayList<>();
        final List<String> corpos = new CopyOnWriteArrayList<>();

        ServidorMtls(Path dir, KeyStore clientes) throws Exception {
            Path arq = dir.resolve("servidor.p12");
            keytool("-genkeypair", "-alias", "srv", "-keyalg", "RSA", "-keysize", "2048", "-validity", "100", "-dname", "CN=localhost, O=Sefaz de Teste, C=BR", "-ext", "san=dns:localhost,ip:127.0.0.1",
                    "-keystore", arq.toString(), "-storetype", "PKCS12", "-storepass", new String(SENHA), "-keypass", new String(SENHA));
            KeyStore sk = abrir(arq);
            confiancaDoAgente = KeyStore.getInstance("PKCS12");
            confiancaDoAgente.load(null, null);
            confiancaDoAgente.setCertificateEntry("srv", sk.getCertificate("srv"));

            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(sk, SENHA);
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            KeyStore aceitos = KeyStore.getInstance("PKCS12");
            aceitos.load(null, null);
            for (String a : java.util.Collections.list(clientes.aliases())) aceitos.setCertificateEntry(a, clientes.getCertificate(a));
            tmf.init(aceitos);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);

            servidor = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            servidor.setHttpsConfigurator(new HttpsConfigurator(ctx) {
                @Override public void configure(HttpsParameters p) {
                    SSLParameters sp = getSSLContext().getDefaultSSLParameters();
                    sp.setNeedClientAuth(true);
                    p.setSSLParameters(sp);
                }
            });
            servidor.createContext("/ws", ex -> {
                X509Certificate c = (X509Certificate) ((com.sun.net.httpserver.HttpsExchange) ex).getSSLSession().getPeerCertificates()[0];
                String cn = c.getSubjectX500Principal().getName();
                apresentados.add(cn);
                corpos.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] r = ("OK:" + cn).getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, r.length);
                try (OutputStream o = ex.getResponseBody()) { o.write(r); }
            });
            servidor.start();
            porta = servidor.getAddress().getPort();
        }

        String url() { return "https://127.0.0.1:" + porta + "/ws"; }

        javax.net.ssl.TrustManager[] confianca() throws Exception {
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(confiancaDoAgente);
            return tmf.getTrustManagers();
        }

        @Override public void close() { servidor.stop(0); }
    }
}
