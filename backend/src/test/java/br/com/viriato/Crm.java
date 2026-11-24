package br.com.viriato;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.viriato.documento.ArmazenamentoArquivos;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Apoio dos testes da Fase 2 (CRM contábil): empresa com administrador, gestor e usuário; certificado de verdade gerado com o keytool. */
final class Crm {
    /** Armazenamento em memória, compartilhado, para conferir o que foi gravado (e apagado). */
    static final Map<String, byte[]> MEMORIA = new ConcurrentHashMap<>();

    @TestConfiguration
    static class StorageFake {
        @Bean @Primary
        ArmazenamentoArquivos storageEmMemoria() {
            return new ArmazenamentoArquivos() {
                public void salvar(String c, InputStream in, long t, String ct) { try { MEMORIA.put(c, in.readAllBytes()); } catch (Exception e) { throw new RuntimeException(e); } }
                public InputStream abrir(String c) { return new ByteArrayInputStream(MEMORIA.get(c)); }
                public void remover(String c) { MEMORIA.remove(c); }
            };
        }
    }

    record Empresa(String admin, String gestor, String usuario, String s, long setorId) {}

    private Crm() {}

    static JsonNode corpo(ObjectMapper json, ResultActions r) throws Exception { return json.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)); }

    static ResultActions enviar(MockMvc mvc, String t, String url, String b) throws Exception {
        return mvc.perform(post(url).header("Authorization", "Bearer " + t).contentType(MediaType.APPLICATION_JSON).content(b));
    }
    static ResultActions ler(MockMvc mvc, String t, String url) throws Exception { return mvc.perform(get(url).header("Authorization", "Bearer " + t)); }
    static ResultActions alterar(MockMvc mvc, String t, String url, String b) throws Exception {
        return mvc.perform(patch(url).header("Authorization", "Bearer " + t).contentType(MediaType.APPLICATION_JSON).content(b));
    }

    static Empresa empresa(MockMvc mvc, ObjectMapper json) throws Exception {
        String s = UUID.randomUUID().toString().substring(0, 6);
        String admin = corpo(json, mvc.perform(post("/api/auth/registrar-empresa").contentType(MediaType.APPLICATION_JSON)
                .content("{\"empresa\":\"E" + s + "\",\"nome\":\"Adm " + s + "\",\"email\":\"a-" + s + "@t.com\",\"senha\":\"senha12345\"}")).andExpect(status().isCreated())).get("token").asText();
        long setor = corpo(json, ler(mvc, admin, "/api/setores")).get(0).get("id").asLong();
        for (String[] p : new String[][]{{"Gestor " + s, "GESTOR"}, {"Usuario " + s, "USUARIO"}}) {
            enviar(mvc, admin, "/api/usuarios", "{\"nome\":\"" + p[0] + "\",\"email\":\"" + p[0].replace(' ', '-') + "@t.com\",\"senha\":\"senha12345\",\"papel\":\"" + p[1] + "\",\"setorId\":" + setor + "}").andExpect(status().isCreated());
        }
        return new Empresa(admin, TestesAuth.entrar(mvc, json, "Gestor " + s), TestesAuth.entrar(mvc, json, "Usuario " + s), s, setor);
    }

    static long cliente(MockMvc mvc, ObjectMapper json, String t, String numero, String nome, String fiscal) throws Exception {
        return corpo(json, enviar(mvc, t, "/api/clientes", "{\"numero\":\"" + numero + "\",\"nome\":\"" + nome + "\"" + (fiscal == null ? "" : ",\"fiscal\":" + fiscal) + "}").andExpect(status().isCreated())).get("id").asLong();
    }

    /** Gera um .pfx com a senha dada (keytool do JDK). {@code diasDeValidade} negativo gera um já vencido. */
    static byte[] pfx(String cn, String senha, int diasDeValidade) throws Exception {
        Path dir = Files.createTempDirectory("pfx");
        Path arq = dir.resolve("c.p12");
        java.util.List<String> cmd = new java.util.ArrayList<>(java.util.List.of(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(), "-genkeypair", "-alias", "empresa",
                "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=" + cn + ", OU=Teste, O=ICP-Brasil, C=BR", "-keystore", arq.toString(),
                "-storetype", "PKCS12", "-storepass", senha, "-keypass", senha));
        if (diasDeValidade >= 0) { cmd.add("-validity"); cmd.add(String.valueOf(Math.max(diasDeValidade, 1))); }
        else { cmd.add("-startdate"); cmd.add("-" + (-diasDeValidade + 30) + "d"); cmd.add("-validity"); cmd.add("30"); }
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String saida = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) throw new IllegalStateException("keytool falhou: " + saida);
        return Files.readAllBytes(arq);
    }
}
