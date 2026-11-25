package br.com.viriato;

import static br.com.viriato.Crm.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Apoio dos testes do agente local: pareamento, envio de certificados (como o agente faria) e um agente simulado que atende tarefas. */
final class Agentes {
    private Agentes() {}

    /** Parte pública (DER em base64) de um certificado gerado com o keytool; é o que o agente enviaria. */
    static String der(String cn, int dias) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(new ByteArrayInputStream(pfx(cn, "senha-teste", dias)), "senha-teste".toCharArray());
        for (String alias : Collections.list(ks.aliases())) {
            if (ks.getCertificate(alias) instanceof X509Certificate x) return Base64.getEncoder().encodeToString(x.getEncoded());
        }
        throw new IllegalStateException("sem certificado");
    }

    /** O usuário gera o código na tela e o agente o troca pelo token. */
    static String parear(MockMvc mvc, ObjectMapper json, String tokenUsuario, String nome) throws Exception {
        String codigo = corpo(json, enviar(mvc, tokenUsuario, "/api/agentes/codigo", "{}").andExpect(status().isCreated())).get("codigo").asText();
        return corpo(json, mvc.perform(post("/api/auth/agente/parear").contentType(MediaType.APPLICATION_JSON)
                .content("{\"codigo\":\"" + codigo + "\",\"nome\":\"" + nome + "\",\"versao\":\"1.0\"}")).andExpect(status().isOk())).get("token").asText();
    }

    static ResultActions contato(MockMvc mvc, String tokenAgente, String corpo) throws Exception {
        return mvc.perform(post("/api/agente/contato").header("Authorization", "Bearer " + tokenAgente).contentType(MediaType.APPLICATION_JSON).content(corpo));
    }

    static ResultActions contato(MockMvc mvc, String tokenAgente, List<String> certificados) throws Exception {
        return contato(mvc, tokenAgente, "{\"versao\":\"1.0\",\"certificados\":[" + String.join(",", certificados.stream().map(c -> "\"" + c + "\"").toList()) + "]}");
    }

    /** Roda em outra linha de execução, como o agente de verdade: consulta o Viriato e responde às tarefas com um corpo fixo. */
    static final class Simulado implements AutoCloseable {
        final AtomicBoolean ativo = new AtomicBoolean(true);
        final List<JsonNode> recebidas = Collections.synchronizedList(new java.util.ArrayList<>());
        private final Thread fio;

        Simulado(MockMvc mvc, ObjectMapper json, String token, int http, byte[] resposta) {
            fio = new Thread(() -> {
                while (ativo.get()) {
                    try {
                        JsonNode r = corpo(json, contato(mvc, token, "{\"versao\":\"1.0\"}").andExpect(status().isOk()));
                        for (JsonNode t : r.get("tarefas")) {
                            recebidas.add(t);
                            mvc.perform(post("/api/agente/tarefas/" + t.get("id").asLong() + "/resultado").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"httpStatus\":" + http + ",\"corpo\":\"" + Base64.getEncoder().encodeToString(resposta) + "\"}")).andExpect(status().isNoContent());
                        }
                        Thread.sleep(100);
                    } catch (Throwable e) {
                        if (ativo.get()) e.printStackTrace();
                    }
                }
            }, "agente-simulado");
            fio.setDaemon(true);
            fio.start();
        }

        @Override public void close() throws InterruptedException { ativo.set(false); fio.join(2000); }
    }
}
