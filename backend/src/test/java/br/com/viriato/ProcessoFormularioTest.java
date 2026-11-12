package br.com.viriato;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.viriato.documento.ArmazenamentoArquivos;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Novo formulário (empresa, entrega, setor, responsável) e a regra "usuário só mexe no que é seu". */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(ProcessoFormularioTest.StorageFake.class)
class ProcessoFormularioTest {

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

    record Cenario(String admin, String gestor, String usuario, long adminId, long usuarioId,
                   long clienteId, long setorId, long entregaId, long fluxoId) {}

    private JsonNode corpo(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private ResultActions enviar(String token, String url, String body) throws Exception {
        return mvc.perform(post(url).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private JsonNode consultar(String token, String url) throws Exception {
        return corpo(mvc.perform(get(url).header("Authorization", "Bearer " + token)).andExpect(status().isOk()));
    }

    private String login(String email) throws Exception {
        return corpo(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"senha\":\"senha12345\"}")).andExpect(status().isOk()))
                .get("token").asText();
    }

    private Cenario cenario() throws Exception {
        String sfx = UUID.randomUUID().toString();
        var reg = corpo(mvc.perform(post("/api/auth/registrar-empresa").contentType(MediaType.APPLICATION_JSON)
                .content("{\"empresa\":\"Emp\",\"nome\":\"Adm\",\"email\":\"adm-" + sfx + "@t.com\",\"senha\":\"senha12345\"}"))
                .andExpect(status().isCreated()));
        String admin = reg.get("token").asText();
        enviar(admin, "/api/usuarios", "{\"nome\":\"Gestor\",\"email\":\"ges-" + sfx + "@t.com\",\"senha\":\"senha12345\",\"papel\":\"GESTOR\"}")
                .andExpect(status().isCreated());
        long usuarioId = corpo(enviar(admin, "/api/usuarios", "{\"nome\":\"Func\",\"email\":\"usu-" + sfx
                + "@t.com\",\"senha\":\"senha12345\",\"papel\":\"USUARIO\"}").andExpect(status().isCreated())).get("id").asLong();
        long clienteId = corpo(enviar(admin, "/api/clientes", "{\"numero\":\"201\",\"nome\":\"Construtora Alfa\"}").andExpect(status().isCreated())).get("id").asLong();
        JsonNode geral = consultar(admin, "/api/setores").get(0);
        long fluxoId = consultar(admin, "/api/fluxos").get(0).get("id").asLong();
        return new Cenario(admin, login("ges-" + sfx + "@t.com"), login("usu-" + sfx + "@t.com"),
                reg.get("usuario").get("id").asLong(), usuarioId, clienteId, geral.get("id").asLong(),
                geral.get("entregas").get(0).get("id").asLong(), fluxoId);
    }

    private String corpoProcesso(Cenario c, long responsavelId) {
        return "{\"clienteId\":" + c.clienteId() + ",\"entregaId\":" + c.entregaId() + ",\"setorId\":" + c.setorId()
                + ",\"responsavelId\":" + responsavelId + "}";
    }

    private long criar(String token, Cenario c, long responsavelId) throws Exception {
        return corpo(enviar(token, "/api/processos", corpoProcesso(c, responsavelId)).andExpect(status().isCreated())).get("id").asLong();
    }

    @Test
    void criaProcessoComEmpresaEntregaSetorEResponsavel_eDerivaTituloEFluxo() throws Exception {
        Cenario c = cenario();
        var p = corpo(enviar(c.admin(), "/api/processos", corpoProcesso(c, c.usuarioId())).andExpect(status().isCreated()));
        assertThat(p.get("clienteNome").asText()).isEqualTo("Construtora Alfa");
        assertThat(p.get("entregaNome").asText()).isEqualTo("Atendimento geral");
        assertThat(p.get("setorNome").asText()).isEqualTo("Geral");
        assertThat(p.get("responsavelNome").asText()).isEqualTo("Func");
        assertThat(p.get("titulo").asText()).isEqualTo("Atendimento geral — Construtora Alfa");
        assertThat(p.get("fluxoId").asLong()).isEqualTo(c.fluxoId());   // o fluxo vem da entrega
        assertThat(p.get("etapaAtual").asText()).isEqualTo("Abertura");
    }

    @Test
    void camposObrigatoriosFaltandoRetorna400() throws Exception {
        Cenario c = cenario();
        enviar(c.admin(), "/api/processos", "{\"clienteId\":" + c.clienteId() + ",\"setorId\":" + c.setorId()
                + ",\"responsavelId\":" + c.usuarioId() + "}").andExpect(status().isBadRequest());   // sem entrega
        enviar(c.admin(), "/api/processos", "{\"clienteId\":" + c.clienteId() + ",\"entregaId\":" + c.entregaId()
                + ",\"setorId\":" + c.setorId() + "}").andExpect(status().isBadRequest());           // sem responsável
    }

    @Test
    void setorSoPodeAbrirProcessoDeEntregaQueFaz() throws Exception {
        Cenario c = cenario();
        long outraEntrega = corpo(enviar(c.admin(), "/api/entregas", "{\"nome\":\"Laudo técnico\",\"fluxoId\":" + c.fluxoId() + "}")
                .andExpect(status().isCreated())).get("id").asLong();
        // o setor Geral não faz "Laudo técnico"
        var resp = corpo(enviar(c.admin(), "/api/processos", "{\"clienteId\":" + c.clienteId() + ",\"entregaId\":" + outraEntrega
                + ",\"setorId\":" + c.setorId() + ",\"responsavelId\":" + c.usuarioId() + "}").andExpect(status().isUnprocessableEntity()));
        assertThat(resp.get("erro").asText()).contains("não faz");

        // depois de liberar a entrega para o setor, passa
        mvc.perform(patch("/api/setores/" + c.setorId()).header("Authorization", "Bearer " + c.gestor())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"entregaIds\":[" + c.entregaId() + "," + outraEntrega + "]}")).andExpect(status().isOk());
        enviar(c.admin(), "/api/processos", "{\"clienteId\":" + c.clienteId() + ",\"entregaId\":" + outraEntrega
                + ",\"setorId\":" + c.setorId() + ",\"responsavelId\":" + c.usuarioId() + "}").andExpect(status().isCreated());
    }

    @Test
    void empresaEntregaOuFluxoInativosSaoRecusados() throws Exception {
        Cenario c = cenario();
        mvc.perform(patch("/api/clientes/" + c.clienteId()).header("Authorization", "Bearer " + c.admin())
                .contentType(MediaType.APPLICATION_JSON).content("{\"ativo\":false}")).andExpect(status().isOk());
        enviar(c.admin(), "/api/processos", corpoProcesso(c, c.usuarioId())).andExpect(status().isUnprocessableEntity());
        mvc.perform(patch("/api/clientes/" + c.clienteId()).header("Authorization", "Bearer " + c.admin())
                .contentType(MediaType.APPLICATION_JSON).content("{\"ativo\":true}")).andExpect(status().isOk());

        // desativar o fluxo da entrega impede novos processos
        mvc.perform(patch("/api/fluxos/" + c.fluxoId()).header("Authorization", "Bearer " + c.admin())
                .contentType(MediaType.APPLICATION_JSON).content("{\"ativo\":false}")).andExpect(status().isOk());
        var resp = corpo(enviar(c.admin(), "/api/processos", corpoProcesso(c, c.usuarioId())).andExpect(status().isUnprocessableEntity()));
        assertThat(resp.get("erro").asText()).contains("inativo");
    }

    @Test
    void usuarioComumVeTodosOsProcessosMasSoMexeNosSeus() throws Exception {
        Cenario c = cenario();
        long meu = criar(c.admin(), c, c.usuarioId());
        long doAdmin = criar(c.admin(), c, c.adminId());

        // vê todos
        assertThat(consultar(c.usuario(), "/api/processos").get("content")).hasSize(2);
        consultar(c.usuario(), "/api/processos/" + doAdmin);

        // no processo de outra pessoa: nada de movimentar, comentar, anexar nem remover anexo
        var arq = new MockMultipartFile("arquivo", "x.pdf", "application/pdf", "x".getBytes());
        long docDoAdmin = corpo(mvc.perform(multipart("/api/processos/" + doAdmin + "/documentos").file(arq)
                .header("Authorization", "Bearer " + c.admin())).andExpect(status().isCreated())).get("id").asLong();
        long proxima = consultar(c.usuario(), "/api/processos/" + doAdmin).get("proximasEtapas").get(0).get("id").asLong();

        enviar(c.usuario(), "/api/processos/" + doAdmin + "/avancar", "{\"etapaId\":" + proxima + "}").andExpect(status().isUnprocessableEntity());
        enviar(c.usuario(), "/api/processos/" + doAdmin + "/comentarios", "{\"texto\":\"oi\"}").andExpect(status().isUnprocessableEntity());
        mvc.perform(multipart("/api/processos/" + doAdmin + "/documentos").file(arq)
                .header("Authorization", "Bearer " + c.usuario())).andExpect(status().isUnprocessableEntity());
        mvc.perform(delete("/api/documentos/" + docDoAdmin).header("Authorization", "Bearer " + c.usuario()))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(put("/api/processos/" + doAdmin + "/responsavel").header("Authorization", "Bearer " + c.usuario())
                .contentType(MediaType.APPLICATION_JSON).content("{\"responsavelId\":" + c.usuarioId() + "}"))
                .andExpect(status().isUnprocessableEntity());
        enviar(c.usuario(), "/api/processos/" + doAdmin + "/cancelar", "{\"motivo\":\"x\"}").andExpect(status().isUnprocessableEntity());
        // ele continua podendo baixar (ver) o documento
        mvc.perform(get("/api/documentos/" + docDoAdmin + "/download").header("Authorization", "Bearer " + c.usuario()))
                .andExpect(status().isOk());

        // no processo dele: pode tudo isso
        long proximaMeu = consultar(c.usuario(), "/api/processos/" + meu).get("proximasEtapas").get(0).get("id").asLong();
        enviar(c.usuario(), "/api/processos/" + meu + "/comentarios", "{\"texto\":\"ok\"}").andExpect(status().isCreated());
        long docMeu = corpo(mvc.perform(multipart("/api/processos/" + meu + "/documentos").file(arq)
                .header("Authorization", "Bearer " + c.usuario())).andExpect(status().isCreated())).get("id").asLong();
        enviar(c.usuario(), "/api/processos/" + meu + "/avancar", "{\"etapaId\":" + proximaMeu + "}").andExpect(status().isOk());
        mvc.perform(delete("/api/documentos/" + docMeu).header("Authorization", "Bearer " + c.usuario())).andExpect(status().isNoContent());

        // gestor e admin mexem em qualquer processo
        enviar(c.gestor(), "/api/processos/" + meu + "/comentarios", "{\"texto\":\"gestor\"}").andExpect(status().isCreated());
        long proximaAdmin = consultar(c.gestor(), "/api/processos/" + doAdmin).get("proximasEtapas").get(0).get("id").asLong();
        enviar(c.gestor(), "/api/processos/" + doAdmin + "/avancar", "{\"etapaId\":" + proximaAdmin + "}").andExpect(status().isOk());
    }

    @Test
    void listaFiltraPorEmpresaESetor() throws Exception {
        Cenario c = cenario();
        long outroCliente = corpo(enviar(c.admin(), "/api/clientes", "{\"numero\":\"202\",\"nome\":\"Beta Engenharia\"}").andExpect(status().isCreated())).get("id").asLong();
        criar(c.admin(), c, c.usuarioId());
        enviar(c.admin(), "/api/processos", "{\"clienteId\":" + outroCliente + ",\"entregaId\":" + c.entregaId()
                + ",\"setorId\":" + c.setorId() + ",\"responsavelId\":" + c.usuarioId() + "}").andExpect(status().isCreated());

        assertThat(consultar(c.admin(), "/api/processos").get("content")).hasSize(2);
        assertThat(consultar(c.admin(), "/api/processos?clienteId=" + outroCliente).get("content")).hasSize(1);
        assertThat(consultar(c.admin(), "/api/processos?setorId=" + c.setorId()).get("content")).hasSize(2);
        assertThat(consultar(c.admin(), "/api/processos?busca=beta").get("content")).hasSize(1);   // título inclui a empresa
    }
}
