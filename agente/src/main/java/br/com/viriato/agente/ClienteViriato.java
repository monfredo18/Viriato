package br.com.viriato.agente;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;

/** Conversa do agente com o Viriato. Sempre o agente chama o Viriato (nunca o contrário), então basta o Viriato ser alcançável daqui. */
final class ClienteViriato {
    /** O Viriato não reconhece mais este agente (desligado, ou a pessoa saiu da empresa). */
    static final class NaoAutorizado extends IOException {
        NaoAutorizado(String m) { super(m); }
    }

    private final String base;
    private final String token;
    private final HttpClient http;

    ClienteViriato(String servidor, String token, TrustManager[] confianca) throws Exception {
        this.base = servidor.endsWith("/") ? servidor.substring(0, servidor.length() - 1) : servidor;
        this.token = token;
        HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER);
        if (confianca != null) {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, confianca, null);
            b.sslContext(ctx);
        }
        this.http = b.build();
    }

    /** Troca o código gerado na tela do Viriato pelo token do agente. */
    String parear(String codigo, String nome, String versao) throws IOException {
        Map<String, Object> r = enviar("/api/auth/agente/parear", Map.of("codigo", codigo, "nome", nome, "versao", versao), false);
        if (!(r.get("token") instanceof String t)) throw new IOException("Resposta do Viriato sem token");
        return t;
    }

    Map<String, Object> contato(Map<String, Object> corpo) throws IOException { return enviar("/api/agente/contato", corpo, true); }

    void resultado(long id, Map<String, Object> corpo) throws IOException { enviar("/api/agente/tarefas/" + id + "/resultado", corpo, true); }

    private Map<String, Object> enviar(String caminho, Map<String, Object> corpo, boolean autenticado) throws IOException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + caminho)).timeout(Duration.ofSeconds(60)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.escrever(corpo), StandardCharsets.UTF_8));
        if (autenticado) b.header("Authorization", "Bearer " + token);
        try {
            HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int s = res.statusCode();
            if (autenticado && (s == 401 || s == 403)) throw new NaoAutorizado("O Viriato não reconhece mais este agente. Gere um novo código e refaça o pareamento");
            if (s / 100 != 2) throw new IOException("O Viriato respondeu HTTP " + s + mensagem(res.body()));
            String t = res.body();
            return t == null || t.isBlank() ? Map.of() : Json.objeto(t);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrompido", e);
        } catch (IllegalArgumentException e) {
            throw new IOException("Resposta do Viriato ilegível: " + e.getMessage());
        }
    }

    private static String mensagem(String corpo) {
        try {
            return Json.objeto(corpo).get("erro") instanceof String e ? ": " + e : "";
        } catch (RuntimeException e) {
            return "";
        }
    }
}
