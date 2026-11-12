package br.com.viriato;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.viriato.documento.ArmazenamentoArquivos;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.BodyPart;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Automação "avisar a empresa por e-mail": variáveis no texto, ficha em anexo, empresa sem e-mail e falha de envio. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "viriato.mail.habilitado=true")
@Import(AutomacaoEmailTest.StorageFake.class)
class AutomacaoEmailTest {

    @TestConfiguration
    static class StorageFake {
        @Bean @Primary
        ArmazenamentoArquivos storageEmMemoria() {
            Map<String, byte[]> mem = new ConcurrentHashMap<>();
            return new ArmazenamentoArquivos() {
                public void salvar(String c, InputStream in, long t, String ct) {
                    try { mem.put(c, in.readAllBytes()); } catch (Exception e) { throw new RuntimeException(e); }
                }
                public InputStream abrir(String c) { return new ByteArrayInputStream(mem.get(c)); }
                public void remover(String c) { mem.remove(c); }
            };
        }
    }

    @MockBean JavaMailSender sender;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    record C(String admin, String gestor, String usuario, long usuarioId, long clienteId, long setorId, long entregaId, long fluxoId, long analise) {}

    @BeforeEach
    void preparaEmail() {
        reset(sender);
        when(sender.createMimeMessage()).thenAnswer(i -> new MimeMessage((Session) null));
    }

    private JsonNode corpo(ResultActions r) throws Exception { return json.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)); }
    private ResultActions enviar(String t, String url, String b) throws Exception {
        return mvc.perform(post(url).header("Authorization", "Bearer " + t).contentType(MediaType.APPLICATION_JSON).content(b));
    }
    private JsonNode consultar(String t, String url) throws Exception { return corpo(mvc.perform(get(url).header("Authorization", "Bearer " + t)).andExpect(status().isOk())); }
    private long usuario(String admin, String nome, String papel, long setor) throws Exception {
        return corpo(enviar(admin, "/api/usuarios", "{\"nome\":\"" + nome + "\",\"senha\":\"senha12345\",\"papel\":\"" + papel + "\",\"setorId\":" + setor + "}")
                .andExpect(status().isCreated())).get("id").asLong();
    }

    private C cenario(String emailEmpresa) throws Exception {
        String s = UUID.randomUUID().toString().substring(0, 8);
        mvc.perform(post("/api/auth/registrar-empresa").contentType(MediaType.APPLICATION_JSON)
                .content("{\"empresa\":\"E\",\"nome\":\"Adm-" + s + "\",\"email\":\"a-" + s + "@t.com\",\"senha\":\"senha12345\"}")).andExpect(status().isCreated());
        String admin = TestesAuth.entrar(mvc, json, "a-" + s + "@t.com");
        JsonNode geral = consultar(admin, "/api/setores").get(0);
        long setor = geral.get("id").asLong();
        usuario(admin, "Ges-" + s, "GESTOR", setor);
        long u = usuario(admin, "Usu-" + s, "USUARIO", setor);
        long cliente = corpo(enviar(admin, "/api/clientes", "{\"numero\":\"10\",\"nome\":\"Alfa Ltda\"" + (emailEmpresa == null ? "" : ",\"email\":\"" + emailEmpresa + "\"") + "}")
                .andExpect(status().isCreated())).get("id").asLong();
        JsonNode fluxo = consultar(admin, "/api/fluxos").get(0);
        return new C(admin, TestesAuth.entrar(mvc, json, "Ges-" + s), TestesAuth.entrar(mvc, json, "Usu-" + s), u, cliente, setor,
                geral.get("entregas").get(0).get("id").asLong(), fluxo.get("id").asLong(), fluxo.get("etapas").get(1).get("id").asLong());
    }

    private void regra(C c, String json) throws Exception {
        enviar(c.gestor(), "/api/fluxos/" + c.fluxoId() + "/automacoes", "{\"evento\":\"ENTRAR_ETAPA\",\"etapaId\":" + c.analise() + "," + json + "}").andExpect(status().isCreated());
    }
    private long processo(C c) throws Exception {
        return corpo(enviar(c.admin(), "/api/processos", "{\"clienteId\":" + c.clienteId() + ",\"entregaId\":" + c.entregaId() + ",\"setorId\":" + c.setorId()
                + ",\"responsavelId\":" + c.usuarioId() + "}").andExpect(status().isCreated())).get("id").asLong();
    }
    private void avancarParaAnalise(C c, long p) throws Exception {
        enviar(c.usuario(), "/api/processos/" + p + "/avancar", "{\"etapaId\":" + c.analise() + "}").andExpect(status().isOk());
    }
    private JsonNode execucoes(C c, long p) throws Exception { return consultar(c.usuario(), "/api/processos/" + p + "/automacoes"); }

    private static void coletar(Part parte, List<String> arquivos, StringBuilder texto) throws Exception {
        Object conteudo = parte.getContent();
        if (conteudo instanceof Multipart m) {
            for (int i = 0; i < m.getCount(); i++) { BodyPart b = m.getBodyPart(i); coletar(b, arquivos, texto); }
        } else if (parte.getFileName() != null) {
            arquivos.add(parte.getFileName());
        } else if (conteudo instanceof String t) {
            texto.append(t);
        }
    }

    @Test
    void enviaOEmailComVariaveisEAFichaEmAnexo() throws Exception {
        C c = cenario("contato@alfa.com");
        regra(c, "\"tipo\":\"GERAR_DOCUMENTO\"");
        regra(c, "\"tipo\":\"EMAIL_EMPRESA\",\"assunto\":\"Processo {{numero}} em {{etapa}}\",\"anexarDocumento\":true,"
                + "\"mensagem\":\"Olá, {{empresa}}! A entrega {{entrega}} chegou à etapa {{etapa}} (responsável: {{responsavel}}). Prazo: {{prazo}}. {{desconhecida}}\"");
        long p = processo(c);
        avancarParaAnalise(c, p);

        ArgumentCaptor<MimeMessage> msg = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender, times(1)).send(msg.capture());
        MimeMessage m = msg.getValue();
        assertThat(m.getAllRecipients()[0].toString()).isEqualTo("contato@alfa.com");
        assertThat(m.getSubject()).isEqualTo("Processo 10 em Análise");
        List<String> arquivos = new ArrayList<>();
        StringBuilder texto = new StringBuilder();
        coletar(m, arquivos, texto);
        assertThat(arquivos).containsExactly("Ficha-10-Análise.pdf");
        assertThat(texto.toString()).contains("Olá, Alfa Ltda!").contains("Atendimento geral").contains("etapa Análise")
                .contains("responsável: Usu-").contains("{{desconhecida}}");    // variável desconhecida fica visível

        JsonNode log = execucoes(c, p);
        assertThat(log).hasSize(2);   // ficha e e-mail
        assertThat(log.get(0).get("tipo").asText()).isEqualTo("EMAIL_EMPRESA");
        assertThat(log.get(0).get("status").asText()).isEqualTo("OK");
        assertThat(log.get(0).get("mensagem").asText()).contains("contato@alfa.com").contains("ficha em anexo");
        assertThat(log.get(1).get("tipo").asText()).isEqualTo("GERAR_DOCUMENTO");   // a ficha roda antes do e-mail
    }

    @Test
    void assuntoNaoAceitaQuebraDeLinha() throws Exception {
        C c = cenario("contato@alfa.com");
        regra(c, "\"tipo\":\"EMAIL_EMPRESA\",\"assunto\":\"Olá\\r\\nBcc: intruso@mal.com {{empresa}}\",\"mensagem\":\"texto\"");
        avancarParaAnalise(c, processo(c));
        ArgumentCaptor<MimeMessage> msg = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(msg.capture());
        assertThat(msg.getValue().getSubject()).doesNotContain("\r").doesNotContain("\n").contains("Alfa Ltda");
        assertThat(msg.getValue().getHeader("Bcc")).isNull();
    }

    @Test
    void semAnexoQuandoNaoHaRegraDeDocumento_eEmpresaSemEmailEhIgnorada() throws Exception {
        C c = cenario("contato@alfa.com");
        regra(c, "\"tipo\":\"EMAIL_EMPRESA\",\"assunto\":\"Aviso\",\"mensagem\":\"texto\",\"anexarDocumento\":true");
        long p = processo(c);
        avancarParaAnalise(c, p);
        ArgumentCaptor<MimeMessage> msg = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(msg.capture());
        List<String> arquivos = new ArrayList<>();
        coletar(msg.getValue(), arquivos, new StringBuilder());
        assertThat(arquivos).isEmpty();
        assertThat(execucoes(c, p).get(0).get("mensagem").asText()).contains("sem anexo");

        // empresa sem e-mail: nada é enviado e o registro explica
        C sem = cenario(null);
        regra(sem, "\"tipo\":\"EMAIL_EMPRESA\",\"assunto\":\"Aviso\",\"mensagem\":\"texto\"");
        long p2 = processo(sem);
        avancarParaAnalise(sem, p2);
        verify(sender, times(1)).send(any(MimeMessage.class));   // continua só o envio anterior
        JsonNode log = execucoes(sem, p2);
        assertThat(log.get(0).get("status").asText()).isEqualTo("IGNORADA");
        assertThat(log.get(0).get("mensagem").asText()).contains("não tem e-mail");
    }

    @Test
    void falhaNoEnvioFicaRegistradaENaoDesfazOMovimento() throws Exception {
        C c = cenario("contato@alfa.com");
        regra(c, "\"tipo\":\"EMAIL_EMPRESA\",\"assunto\":\"Aviso\",\"mensagem\":\"texto\"");
        doThrow(new MailSendException("servidor de e-mail fora do ar")).when(sender).send(any(MimeMessage.class));
        long p = processo(c);
        avancarParaAnalise(c, p);
        assertThat(consultar(c.usuario(), "/api/processos/" + p).get("processo").get("etapaAtual").asText()).isEqualTo("Análise");
        JsonNode log = execucoes(c, p);
        assertThat(log.get(0).get("status").asText()).isEqualTo("FALHA");
        assertThat(log.get(0).get("mensagem").asText()).contains("fora do ar");
    }
}
