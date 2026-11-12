package br.com.viriato;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import br.com.viriato.documento.ArmazenamentoArquivos;
import br.com.viriato.notificacao.PrazoScheduler;
import br.com.viriato.processo.ProcessoRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FluxoCompletoTest {

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

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired PrazoScheduler scheduler;
    @Autowired ProcessoRepository processos;

    record Empresa(String token, long usuarioId, long fluxoId, long clienteId, long setorId, long entregaId) {}

    private JsonNode corpo(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private Empresa novaEmpresa() throws Exception {
        String email = "admin-" + UUID.randomUUID() + "@teste.com";
        var res = corpo(mvc.perform(post("/api/auth/registrar-empresa").contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"empresa":"Empresa X","nome":"Admin","email":"%s","senha":"senha12345"}""".formatted(email)))
                .andExpect(status().isCreated()));
        String token = res.get("token").asText();
        var fluxos = corpo(mvc.perform(get("/api/fluxos").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()));
        long clienteId = corpo(mvc.perform(post("/api/clientes").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"numero\":\"301\",\"nome\":\"Cliente Teste\"}"))
                .andExpect(status().isCreated())).get("id").asLong();
        var setor = corpo(mvc.perform(get("/api/setores").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())).get(0);
        return new Empresa(token, res.get("usuario").get("id").asLong(), fluxos.get(0).get("id").asLong(), clienteId,
                setor.get("id").asLong(), setor.get("entregas").get(0).get("id").asLong());
    }

    private JsonNode criarProcesso(Empresa e, String titulo) throws Exception {
        return corpo(mvc.perform(post("/api/processos").header("Authorization", "Bearer " + e.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"clienteId":%d,"entregaId":%d,"setorId":%d,"responsavelId":%d,"descricao":"%s","camposExtras":{"cliente":"ACME"}}"""
                                .formatted(e.clienteId(), e.entregaId(), e.setorId(), e.usuarioId(), titulo)))
                .andExpect(status().isCreated()));
    }

    @Test
    void semTokenRetorna401() throws Exception {
        mvc.perform(get("/api/processos")).andExpect(status().isUnauthorized());
    }

    @Test
    void ciclo_criar_avancar_concluir_com_historico() throws Exception {
        Empresa e = novaEmpresa();
        JsonNode p = criarProcesso(e, "Contrato A");
        long id = p.get("id").asLong();
        assertThat(p.get("etapaAtual").asText()).isEqualTo("Abertura");
        assertThat(p.get("numero").asText()).isEqualTo("301");   // o número do processo é o número da empresa
        assertThat(p.get("camposExtras").get("cliente").asText()).isEqualTo("ACME");

        // percorre o fluxo padrão até o fim
        for (int i = 0; i < 3; i++) {
            var det = corpo(mvc.perform(get("/api/processos/" + id).header("Authorization", "Bearer " + e.token()))
                    .andExpect(status().isOk()));
            long proxima = det.get("proximasEtapas").get(0).get("id").asLong();
            mvc.perform(post("/api/processos/" + id + "/avancar").header("Authorization", "Bearer " + e.token())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"etapaId\":" + proxima + ",\"comentario\":\"ok\"}")).andExpect(status().isOk());
        }
        var fim = corpo(mvc.perform(get("/api/processos/" + id).header("Authorization", "Bearer " + e.token()))
                .andExpect(status().isOk()));
        assertThat(fim.get("processo").get("status").asText()).isEqualTo("CONCLUIDO");
        assertThat(fim.get("processo").get("etapaFinal").asBoolean()).isTrue();
        assertThat(fim.get("proximasEtapas")).isEmpty();
        assertThat(fim.get("historico").size()).isEqualTo(4); // CRIADO + 3 MOVIDO
    }

    @Test
    void transicaoForaDoFluxoE_rejeitada() throws Exception {
        Empresa e = novaEmpresa();
        long id = criarProcesso(e, "Pulo").get("id").asLong();
        var fluxo = corpo(mvc.perform(get("/api/fluxos/" + e.fluxoId()).header("Authorization", "Bearer " + e.token())));
        long etapaFinal = fluxo.get("etapas").get(3).get("id").asLong(); // Abertura -> Concluído não existe
        mvc.perform(post("/api/processos/" + id + "/avancar").header("Authorization", "Bearer " + e.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"etapaId\":" + etapaFinal + "}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void empresaNaoEnxergaDadosDeOutraEmpresa() throws Exception {
        Empresa a = novaEmpresa();
        Empresa b = novaEmpresa();
        long idA = criarProcesso(a, "Segredo da A").get("id").asLong();

        mvc.perform(get("/api/processos/" + idA).header("Authorization", "Bearer " + b.token()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/processos/" + idA + "/comentarios").header("Authorization", "Bearer " + b.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"texto\":\"invasão\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/processos/" + idA + "/documentos").header("Authorization", "Bearer " + b.token()))
                .andExpect(status().isNotFound());
        var lista = corpo(mvc.perform(get("/api/processos").header("Authorization", "Bearer " + b.token()))
                .andExpect(status().isOk()));
        assertThat(lista.get("content")).isEmpty();
        // B tampouco consegue abrir processo usando empresa, entrega e setor de A
        mvc.perform(post("/api/processos").header("Authorization", "Bearer " + b.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"clienteId\":" + a.clienteId() + ",\"entregaId\":" + a.entregaId() + ",\"setorId\":" + a.setorId()
                        + ",\"responsavelId\":" + b.usuarioId() + "}")).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void usuarioComum_naoCriaFluxo_nemUsuarios() throws Exception {
        Empresa e = novaEmpresa();
        String emailU = "user-" + UUID.randomUUID() + "@teste.com";
        mvc.perform(post("/api/usuarios").header("Authorization", "Bearer " + e.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nome\":\"Op\",\"email\":\"" + emailU + "\",\"senha\":\"senha12345\",\"papel\":\"USUARIO\"}"))
                .andExpect(status().isCreated());
        String tokenU = corpo(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + emailU + "\",\"senha\":\"senha12345\"}")).andExpect(status().isOk()))
                .get("token").asText();

        mvc.perform(post("/api/fluxos").header("Authorization", "Bearer " + tokenU)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nome\":\"F\",\"etapas\":[]}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/usuarios").header("Authorization", "Bearer " + tokenU)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nome\":\"X\",\"email\":\"x@x.com\",\"senha\":\"senha12345\",\"papel\":\"ADMIN\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void loginComSenhaErradaRetorna401() throws Exception {
        Empresa e = novaEmpresa();
        var me = corpo(mvc.perform(get("/api/me").header("Authorization", "Bearer " + e.token())).andExpect(status().isOk()));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + me.get("email").asText() + "\",\"senha\":\"errada123\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void uploadEDownloadDeDocumento_eIsolamento() throws Exception {
        Empresa a = novaEmpresa();
        Empresa b = novaEmpresa();
        long idP = criarProcesso(a, "Com anexo").get("id").asLong();

        var arq = new MockMultipartFile("arquivo", "../../etc/passwd.pdf", "application/pdf", "conteudo".getBytes());
        var doc = corpo(mvc.perform(multipart("/api/processos/" + idP + "/documentos").file(arq)
                .header("Authorization", "Bearer " + a.token())).andExpect(status().isCreated()));
        assertThat(doc.get("nome").asText()).doesNotContain("/").doesNotContain("..");

        var bytes = mvc.perform(get("/api/documentos/" + doc.get("id").asLong() + "/download")
                .header("Authorization", "Bearer " + a.token())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(bytes)).isEqualTo("conteudo");

        mvc.perform(get("/api/documentos/" + doc.get("id").asLong() + "/download")
                .header("Authorization", "Bearer " + b.token())).andExpect(status().isNotFound());
    }

    @Test
    void agendadorNotificaPrazoVencidoUmaVezSo() throws Exception {
        Empresa e = novaEmpresa();
        // o alerta de prazo vai para o gestor do setor do processo
        String emailG = "gestor-" + UUID.randomUUID() + "@teste.com";
        mvc.perform(post("/api/usuarios").header("Authorization", "Bearer " + e.token()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"nome\":\"Gestor\",\"email\":\"" + emailG + "\",\"senha\":\"senha12345\",\"papel\":\"GESTOR\",\"setorId\":" + e.setorId() + "}"))
                .andExpect(status().isCreated());
        String tokenG = corpo(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + emailG + "\",\"senha\":\"senha12345\"}")).andExpect(status().isOk())).get("token").asText();
        long id = criarProcesso(e, "Atrasado").get("id").asLong();
        var p = processos.findById(id).orElseThrow();
        p.setPrazoEtapa(LocalDate.now().minusDays(3));
        processos.save(p);

        assertThat(scheduler.verificar()).isGreaterThanOrEqualTo(1);
        assertThat(scheduler.verificar()).isZero(); // idempotente: não repete o alerta

        var n = corpo(mvc.perform(get("/api/notificacoes/nao-lidas").header("Authorization", "Bearer " + tokenG))
                .andExpect(status().isOk()));
        assertThat(n.get("total").asLong()).isEqualTo(2);        // "Novo processo aberto" + o alerta de prazo
        long alertas = 0;
        for (var x : corpo(mvc.perform(get("/api/notificacoes").header("Authorization", "Bearer " + tokenG)).andExpect(status().isOk()))) {
            if (x.get("titulo").asText().equals("Prazo vencido")) alertas++;
        }
        assertThat(alertas).isEqualTo(1);                          // e o alerta não se repete
        var doAdmin = corpo(mvc.perform(get("/api/notificacoes/nao-lidas").header("Authorization", "Bearer " + e.token())).andExpect(status().isOk()));
        assertThat(doAdmin.get("total").asLong()).isZero();      // o administrador não recebe
        var lista = corpo(mvc.perform(get("/api/processos?atrasados=true").header("Authorization", "Bearer " + e.token())));
        assertThat(lista.get("content").size()).isEqualTo(1);
    }
}
