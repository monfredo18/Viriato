package br.com.viriato.agente;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * O que o agente lembra entre as execuções: o endereço do Viriato, o token (entregue uma única vez no pareamento) e o nome do computador.
 * Fica na pasta do usuário ({@code %APPDATA%\ViriatoAgente} no Windows). Quem tiver o arquivo age como este agente até ser desligado
 * no Viriato, por isso a pasta é do usuário e, onde o sistema permite, só ele lê.
 */
record Configuracao(String servidor, String token, String nome, List<String> hostsPermitidos, String truststore, String truststoreSenha) {
    static final List<String> HOSTS_PADRAO = List.of("fazenda.gov.br", "gov.br");

    static Path pasta() {
        String forcada = System.getenv("VIRIATO_AGENTE_DIR");
        if (forcada != null && !forcada.isBlank()) return Path.of(forcada);
        String appdata = System.getenv("APPDATA");
        if (appdata != null && !appdata.isBlank()) return Path.of(appdata, "ViriatoAgente");
        return Path.of(System.getProperty("user.home"), ".viriato-agente");
    }

    static Path arquivo(Path pasta) { return pasta.resolve("agente.json"); }

    static Configuracao carregar(Path pasta) throws IOException {
        Path a = arquivo(pasta);
        if (!Files.exists(a)) throw new IOException("O agente ainda não foi ligado ao Viriato. Use: parear <endereço> <código>");
        Map<String, Object> m = Json.objeto(Files.readString(a, StandardCharsets.UTF_8));
        List<String> hosts = m.get("hostsPermitidos") instanceof List<?> l ? l.stream().map(String::valueOf).toList() : HOSTS_PADRAO;
        return new Configuracao(texto(m, "servidor"), texto(m, "token"), texto(m, "nome"), hosts, texto(m, "truststore"), texto(m, "truststoreSenha"));
    }

    void salvar(Path pasta) throws IOException {
        Files.createDirectories(pasta);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("servidor", servidor);
        m.put("token", token);
        m.put("nome", nome);
        m.put("hostsPermitidos", hostsPermitidos);
        if (truststore != null) { m.put("truststore", truststore); m.put("truststoreSenha", truststoreSenha); }
        Path a = arquivo(pasta);
        Files.writeString(a, Json.escrever(m), StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(a, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException e) {
            // Windows: vale o acesso da pasta do usuário
        }
    }

    private static String texto(Map<String, Object> m, String k) { return m.get(k) instanceof String s && !s.isBlank() ? s : null; }
}
