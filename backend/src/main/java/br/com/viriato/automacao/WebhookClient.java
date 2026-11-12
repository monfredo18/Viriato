package br.com.viriato.automacao;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Envia o JSON do evento. Sem redirecionamentos, com prazo curto, e assinado (HMAC-SHA256) quando há segredo. */
@Component
public class WebhookClient {
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final boolean permitirRedeInterna;

    public WebhookClient(@Value("${viriato.automacao.permitir-rede-interna:false}") boolean permitirRedeInterna) {
        this.permitirRedeInterna = permitirRedeInterna;
    }

    public boolean permitirRedeInterna() { return permitirRedeInterna; }

    /** @return o código HTTP da resposta */
    public int enviar(String url, String segredo, String evento, String json) throws Exception {
        URI uri = UrlWebhook.validar(url, permitirRedeInterna);
        UrlWebhook.conferirDestino(uri, permitirRedeInterna);
        HttpRequest.Builder req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("User-Agent", "Viriato-Webhook/1")
                .header("X-Viriato-Evento", evento)
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        if (segredo != null && !segredo.isBlank()) req.header("X-Viriato-Assinatura", "sha256=" + assinar(segredo, json));
        return http.send(req.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    public static String assinar(String segredo, String corpo) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(segredo.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(corpo.getBytes(StandardCharsets.UTF_8)));
    }
}
