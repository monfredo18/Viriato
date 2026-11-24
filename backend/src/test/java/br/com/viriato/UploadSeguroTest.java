package br.com.viriato;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.viriato.documento.ClamAvScanner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Upload: extensão permitida, conteúdo do tipo da extensão (magic bytes) e antivírus (ClamAV simulado por um servidor local). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UploadSeguroTest {
    @org.springframework.boot.test.context.TestConfiguration
    static class StorageFake {
        @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary
        br.com.viriato.documento.ArmazenamentoArquivos storageEmMemoria() {
            var mem = new java.util.concurrent.ConcurrentHashMap<String, byte[]>();
            return new br.com.viriato.documento.ArmazenamentoArquivos() {
                public void salvar(String c, java.io.InputStream in, long t, String ct) { try { mem.put(c, in.readAllBytes()); } catch (Exception e) { throw new RuntimeException(e); } }
                public java.io.InputStream abrir(String c) { return new ByteArrayInputStream(mem.get(c)); }
                public void remover(String c) { mem.remove(c); }
            };
        }
    }

    /** Falso clamd: responde FOUND se o arquivo contiver "EICAR", senão OK. */
    static final ServerSocket CLAMD;
    static {
        try {
            CLAMD = new ServerSocket(0);
            Thread t = new Thread(() -> {
                while (!CLAMD.isClosed()) {
                    try (var c = CLAMD.accept()) {
                        c.setSoTimeout(5000);
                        DataInputStream in = new DataInputStream(c.getInputStream());
                        in.readNBytes("zINSTREAM\0".length());
                        var corpo = new java.io.ByteArrayOutputStream();
                        int n;
                        while ((n = in.readInt()) > 0) corpo.write(in.readNBytes(n));
                        boolean infectado = corpo.toString(StandardCharsets.ISO_8859_1).contains("EICAR");
                        c.getOutputStream().write((infectado ? "stream: Eicar-Test-Signature FOUND\0" : "stream: OK\0").getBytes(StandardCharsets.US_ASCII));
                    } catch (IOException e) { /* conexão encerrada */ }
                }
            });
            t.setDaemon(true);
            t.start();
        } catch (IOException e) { throw new IllegalStateException(e); }
    }

    @DynamicPropertySource
    static void antivirus(DynamicPropertyRegistry r) {
        r.add("viriato.antivirus.clamav.habilitado", () -> "true");
        r.add("viriato.antivirus.clamav.porta", () -> CLAMD.getLocalPort());
        r.add("viriato.antivirus.clamav.host", () -> "127.0.0.1");
    }

    @AfterAll
    static void fechar() throws IOException { CLAMD.close(); }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private JsonNode corpo(ResultActions r) throws Exception { return json.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)); }

    private record Cena(String admin, long processo) {}

    private Cena cena() throws Exception {
        String s = UUID.randomUUID().toString().substring(0, 6);
        String admin = corpo(mvc.perform(post("/api/auth/registrar-empresa").contentType(MediaType.APPLICATION_JSON)
                .content("{\"empresa\":\"E\",\"nome\":\"Adm " + s + "\",\"email\":\"a-" + s + "@t.com\",\"senha\":\"senha12345\"}")).andExpect(status().isCreated())).get("token").asText();
        String h = "Bearer " + admin;
        JsonNode geral = corpo(mvc.perform(get("/api/setores").header("Authorization", h))).get(0);
        long cli = corpo(mvc.perform(post("/api/clientes").header("Authorization", h).contentType(MediaType.APPLICATION_JSON).content("{\"numero\":\"" + (1000 + (int) (Math.random() * 8000)) + "\",\"nome\":\"Cliente\"}")).andExpect(status().isCreated())).get("id").asLong();
        long eu = corpo(mvc.perform(get("/api/me").header("Authorization", h))).get("id").asLong();
        long p = corpo(mvc.perform(post("/api/processos").header("Authorization", h).contentType(MediaType.APPLICATION_JSON)
                .content("{\"clienteId\":" + cli + ",\"entregaId\":" + geral.get("entregas").get(0).get("id").asLong() + ",\"setorId\":" + geral.get("id").asLong() + ",\"responsavelId\":" + eu + "}")).andExpect(status().isCreated())).get("id").asLong();
        return new Cena(admin, p);
    }

    private ResultActions enviar(Cena c, String nome, String tipo, byte[] bytes) throws Exception {
        return mvc.perform(multipart("/api/processos/" + c.processo() + "/documentos").file(new MockMultipartFile("arquivo", nome, tipo, bytes)).header("Authorization", "Bearer " + c.admin()));
    }

    @Test
    void arquivoLegitimoEhAceitoEOTipoVemDaExtensao() throws Exception {
        Cena c = cena();
        JsonNode d = corpo(enviar(c, "laudo.pdf", "application/x-qualquer-coisa", "%PDF-1.7\nconteudo".getBytes()).andExpect(status().isCreated()));
        assertThat(d.get("contentType").asText()).isEqualTo("application/pdf");   // não confia no tipo informado
        enviar(c, "notas.xml", "text/xml", "<?xml version=\"1.0\"?><nfe/>".getBytes()).andExpect(status().isCreated());
        enviar(c, "extrato.ofx", "text/plain", "OFXHEADER:100\nDATA:OFXSGML\n".getBytes()).andExpect(status().isCreated());
        enviar(c, "tabela.csv", "text/csv", "a;b;c\n1;2;3\n".getBytes()).andExpect(status().isCreated());
        enviar(c, "pacote.zip", "application/zip", new byte[] { 'P', 'K', 3, 4, 0, 0 }).andExpect(status().isCreated());
        enviar(c, "foto.png", "image/png", new byte[] { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0 }).andExpect(status().isCreated());
    }

    @Test
    void extensaoForaDaListaERecusada() throws Exception {
        Cena c = cena();
        for (String nome : new String[] { "programa.exe", "script.sh", "pagina.html", "macro.docm", "semextensao" }) {
            String msg = corpo(enviar(c, nome, "application/octet-stream", "conteudo".getBytes()).andExpect(status().isUnprocessableEntity())).get("erro").asText();
            assertThat(msg).contains("Tipo de arquivo não permitido");
        }
    }

    @Test
    void conteudoQueNaoCorrespondeAExtensaoERecusado() throws Exception {
        Cena c = cena();
        byte[] executavel = { 'M', 'Z', (byte) 0x90, 0, 3, 0, 0, 0, 4, 0 };
        enviar(c, "inofensivo.pdf", "application/pdf", executavel).andExpect(status().isUnprocessableEntity());
        enviar(c, "planilha.xlsx", "application/vnd.ms-excel", "texto simples".getBytes()).andExpect(status().isUnprocessableEntity());
        enviar(c, "foto.jpg", "image/jpeg", "%PDF-1.4".getBytes()).andExpect(status().isUnprocessableEntity());
        enviar(c, "dados.xml", "text/xml", new byte[] { 0, 1, 2, 3, '<' }).andExpect(status().isUnprocessableEntity());
        enviar(c, "extrato.csv", "text/csv", new byte[] { 'a', 0, 'b', 0 }).andExpect(status().isUnprocessableEntity());
        enviar(c, "vazio.pdf", "application/pdf", new byte[0]).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void antivirusRecusaArquivoInfectadoEAceitaOLimpo() throws Exception {
        Cena c = cena();
        String msg = corpo(enviar(c, "contrato.pdf", "application/pdf", "%PDF-1.4 EICAR".getBytes()).andExpect(status().isUnprocessableEntity())).get("erro").asText();
        assertThat(msg).contains("antivírus").contains("Eicar-Test-Signature");
        enviar(c, "contrato.pdf", "application/pdf", "%PDF-1.4 limpo".getBytes()).andExpect(status().isCreated());
    }

    @Test
    void clamAvScannerFalaOProtocoloInstream() throws Exception {
        var scanner = new ClamAvScanner("127.0.0.1", CLAMD.getLocalPort(), 3000);
        assertThat(scanner.examinar("a.pdf", new ByteArrayInputStream("limpo".getBytes()))).isNull();
        assertThat(scanner.examinar("a.pdf", new ByteArrayInputStream("EICAR".getBytes()))).isEqualTo("Eicar-Test-Signature");
    }

    @Test
    void semAntivirusDisponivelOEnvioFalhaFechado() throws Exception {
        var morto = new ClamAvScanner("127.0.0.1", 1, 500);
        org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () -> morto.examinar("a.pdf", new ByteArrayInputStream("x".getBytes())));
    }
}
