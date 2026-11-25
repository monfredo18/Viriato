package br.com.viriato.agente;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyStore;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedKeyManager;

/**
 * Faz a chamada HTTPS com autenticação por certificado (TLS mútuo) usando um certificado do computador, escolhido pela impressão
 * digital. A chave privada fica no repositório (Windows ou token); o agente só a usa para a negociação TLS. Por segurança, só chama
 * endereços HTTPS de domínios permitidos (por padrão, {@code gov.br}): um pedido do servidor para qualquer outro endereço é recusado.
 */
final class ExecutorHttps {
    static final int MAXIMO_RESPOSTA = 6 * 1024 * 1024;

    record Resposta(int httpStatus, byte[] corpo) {}

    private final RepositorioDeCertificados repositorio;
    private final List<String> hostsPermitidos;
    private final boolean exigirPortaPadrao;
    private final TrustManager[] confianca;
    private final Duration limite;

    ExecutorHttps(RepositorioDeCertificados repositorio, List<String> hostsPermitidos, boolean exigirPortaPadrao, TrustManager[] confianca, Duration limite) {
        this.repositorio = repositorio;
        this.hostsPermitidos = hostsPermitidos;
        this.exigirPortaPadrao = exigirPortaPadrao;
        this.confianca = confianca;
        this.limite = limite;
    }

    /** Confere o endereço antes de qualquer conexão. */
    void conferirDestino(String url) throws IOException {
        URI u;
        try {
            u = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IOException("Endereço inválido");
        }
        if (!"https".equalsIgnoreCase(u.getScheme())) throw new IOException("Só são permitidos endereços HTTPS");
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase();
        if (u.getUserInfo() != null) throw new IOException("Endereço com usuário não é permitido");
        if (exigirPortaPadrao && u.getPort() != -1 && u.getPort() != 443) throw new IOException("Porta não permitida");
        boolean ok = hostsPermitidos.stream().map(String::toLowerCase).anyMatch(h -> host.equals(h) || host.endsWith("." + h));
        if (!ok) throw new IOException("O agente não chama o endereço " + host + " (fora dos domínios permitidos)");
    }

    Resposta postar(String url, String contentType, byte[] corpo, String thumbprint) throws Exception {
        conferirDestino(url);
        RepositorioDeCertificados.Local escolhido = repositorio.listar().stream().filter(l -> l.thumbprint().equalsIgnoreCase(thumbprint)).findFirst()
                .orElseThrow(() -> new IOException("O certificado não está instalado neste computador (ou não tem chave privada)"));
        KeyStore ks = repositorio.repositorio();
        PrivateKey chave = (PrivateKey) ks.getKey(escolhido.alias(), repositorio.senhaDaChave());
        if (chave == null) throw new IOException("O certificado não tem chave privada utilizável neste computador");
        Certificate[] cadeia = ks.getCertificateChain(escolhido.alias());
        X509Certificate[] x = new X509Certificate[cadeia == null ? 1 : cadeia.length];
        if (cadeia == null) x[0] = escolhido.certificado();
        else for (int i = 0; i < cadeia.length; i++) x[i] = (X509Certificate) cadeia[i];

        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(new KeyManager[]{new UmCertificado(escolhido.alias(), chave, x)}, confianca, null);
        HttpClient http = HttpClient.newBuilder().sslContext(ctx).sslParameters(new SSLParameters(null, new String[]{"TLSv1.2"}))
                .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(limite).build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(limite).header("Content-Type", contentType == null ? "application/octet-stream" : contentType)
                .POST(HttpRequest.BodyPublishers.ofByteArray(corpo)).build();
        HttpResponse<InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = res.body()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > MAXIMO_RESPOSTA) throw new IOException("A resposta passou de " + MAXIMO_RESPOSTA / 1024 / 1024 + " MB");
            }
            return new Resposta(res.statusCode(), out.toByteArray());
        }
    }

    /** Apresenta sempre o certificado escolhido, nunca outro do repositório. */
    private static final class UmCertificado extends X509ExtendedKeyManager {
        private final String alias;
        private final PrivateKey chave;
        private final X509Certificate[] cadeia;

        UmCertificado(String alias, PrivateKey chave, X509Certificate[] cadeia) {
            this.alias = alias;
            this.chave = chave;
            this.cadeia = cadeia;
        }

        @Override public String[] getClientAliases(String tipo, Principal[] emissoras) { return new String[]{alias}; }
        @Override public String chooseClientAlias(String[] tipos, Principal[] emissoras, java.net.Socket s) { return alias; }
        @Override public String chooseEngineClientAlias(String[] tipos, Principal[] emissoras, javax.net.ssl.SSLEngine e) { return alias; }
        @Override public String[] getServerAliases(String tipo, Principal[] emissoras) { return null; }
        @Override public String chooseServerAlias(String tipo, Principal[] emissoras, java.net.Socket s) { return null; }
        @Override public X509Certificate[] getCertificateChain(String a) { return alias.equals(a) ? cadeia : null; }
        @Override public PrivateKey getPrivateKey(String a) { return alias.equals(a) ? chave : null; }
    }
}
