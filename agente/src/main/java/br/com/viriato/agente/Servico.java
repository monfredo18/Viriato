package br.com.viriato.agente;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * O laço do agente: a cada poucos segundos avisa o Viriato que está vivo, informa os certificados do computador (quando mudam, e de
 * tempos em tempos) e executa as tarefas que o Viriato pediu, devolvendo o resultado.
 */
final class Servico {
    private static final Logger log = Logger.getLogger("viriato.agente");
    static final Duration RELER_CERTIFICADOS = Duration.ofSeconds(60);
    static final Duration REENVIAR_CERTIFICADOS = Duration.ofMinutes(10);

    private final RepositorioDeCertificados repositorio;
    private final ClienteViriato api;
    private final ExecutorHttps executor;
    private final String versao;

    private List<RepositorioDeCertificados.Local> certificados = List.of();
    private Instant lidoEm = Instant.MIN;
    private Instant enviadoEm = Instant.MIN;
    private String ultimaImpressao = "";

    Servico(RepositorioDeCertificados repositorio, ClienteViriato api, ExecutorHttps executor, String versao) {
        this.repositorio = repositorio;
        this.api = api;
        this.executor = executor;
        this.versao = versao;
    }

    /** Um batimento: devolve quantas tarefas executou (se houve, convém voltar logo, sem esperar). */
    int umCiclo() throws IOException {
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("versao", versao);
        String impressao = null;
        try {
            if (Instant.now().isAfter(lidoEm.plus(RELER_CERTIFICADOS))) {
                certificados = repositorio.listar();
                lidoEm = Instant.now();
            }
            impressao = certificados.stream().map(RepositorioDeCertificados.Local::thumbprint).sorted().reduce("", (a, b) -> a + b);
            if (!impressao.equals(ultimaImpressao) || Instant.now().isAfter(enviadoEm.plus(REENVIAR_CERTIFICADOS))) {
                List<String> ders = new ArrayList<>();
                for (RepositorioDeCertificados.Local l : certificados) ders.add(Base64.getEncoder().encodeToString(l.certificado().getEncoded()));
                corpo.put("certificados", ders);
            }
        } catch (Exception e) {
            log.log(Level.WARNING, "Não foi possível ler os certificados do computador: " + e.getMessage());
        }
        Map<String, Object> r = api.contato(corpo);
        if (corpo.containsKey("certificados")) {
            ultimaImpressao = impressao;
            enviadoEm = Instant.now();
            log.info("Certificados informados ao Viriato: " + certificados.size());
        }
        int feitas = 0;
        if (r.get("tarefas") instanceof List<?> tarefas) {
            for (Object o : tarefas) {
                if (o instanceof Map<?, ?> t) { executar(t); feitas++; }
            }
        }
        return feitas;
    }

    private void executar(Map<?, ?> t) throws IOException {
        long id = ((Number) t.get("id")).longValue();
        Map<String, Object> resultado = new LinkedHashMap<>();
        try {
            if (!"HTTPS_MTLS".equals(t.get("tipo"))) throw new IOException("Tipo de tarefa desconhecido");
            byte[] corpo = Base64.getDecoder().decode(String.valueOf(t.get("corpo")));
            log.info("Tarefa " + id + ": chamada HTTPS para " + t.get("url"));
            ExecutorHttps.Resposta r = executor.postar(String.valueOf(t.get("url")), String.valueOf(t.get("contentType")), corpo, String.valueOf(t.get("thumbprint")));
            resultado.put("httpStatus", r.httpStatus());
            resultado.put("corpo", Base64.getEncoder().encodeToString(r.corpo()));
        } catch (Exception e) {
            log.log(Level.WARNING, "Tarefa " + id + " falhou: " + e);
            String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            resultado.put("erro", m.length() > 400 ? m.substring(0, 400) : m);
        }
        api.resultado(id, resultado);
    }

    /** Roda até ser interrompido ou até o Viriato não reconhecer mais o agente. */
    void executar(Duration intervalo) throws InterruptedException {
        long espera = intervalo.toMillis();
        while (!Thread.currentThread().isInterrupted()) {
            try {
                int feitas = umCiclo();
                espera = intervalo.toMillis();
                if (feitas > 0) continue;
            } catch (ClienteViriato.NaoAutorizado e) {
                log.severe(e.getMessage());
                return;
            } catch (IOException e) {
                log.warning("Viriato indisponível: " + e.getMessage() + " (tentando de novo)");
                espera = Math.min(espera * 2, 60_000);   // sem pressa quando o servidor está fora
            }
            Thread.sleep(espera);
        }
    }
}
