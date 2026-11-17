package br.com.viriato;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.viriato.comum.Datas;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Só a etapa de conclusão tem prazo; etapas novas podem ser criadas, renomeadas e removidas depois. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EtapasEPrazoTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private JsonNode corpo(ResultActions r) throws Exception { return json.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)); }
    private ResultActions enviar(String t, String url, String b) throws Exception {
        return mvc.perform(post(url).header("Authorization", "Bearer " + t).contentType(MediaType.APPLICATION_JSON).content(b));
    }
    private ResultActions alterar(String t, String url, String b) throws Exception {
        return mvc.perform(patch(url).header("Authorization", "Bearer " + t).contentType(MediaType.APPLICATION_JSON).content(b));
    }
    private ResultActions ler(String t, String url) throws Exception { return mvc.perform(get(url).header("Authorization", "Bearer " + t)); }

    private String admin() throws Exception {
        String s = UUID.randomUUID().toString().substring(0, 6);
        return corpo(mvc.perform(post("/api/auth/registrar-empresa").contentType(MediaType.APPLICATION_JSON)
                .content("{\"empresa\":\"E\",\"nome\":\"Adm " + s + "\",\"email\":\"a-" + s + "@t.com\",\"senha\":\"senha12345\"}")).andExpect(status().isCreated())).get("token").asText();
    }

    private String nomes(JsonNode fluxo) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode e : fluxo.get("etapas")) sb.append(e.get("nome").asText()).append('|');
        return sb.toString();
    }

    private JsonNode fluxoPadrao(String admin) throws Exception { return corpo(ler(admin, "/api/fluxos")).get(0); }

    @Test
    void soAEtapaDeConclusaoTemPrazoEOProcessoUsaEsseUnicoPrazo() throws Exception {
        String adm = admin();
        JsonNode f = fluxoPadrao(adm);
        for (JsonNode e : f.get("etapas")) {
            if (e.get("finalEtapa").asBoolean()) assertThat(e.get("prazoDias").asInt()).isEqualTo(10);
            else assertThat(e.get("prazoDias").isNull()).isTrue();
        }
        // prazo informado em etapa que não é a de conclusão é ignorado na criação
        JsonNode novo = corpo(enviar(adm, "/api/fluxos", "{\"nome\":\"Teste\",\"etapas\":[{\"nome\":\"A\",\"prazoDias\":3,\"inicial\":true},{\"nome\":\"B\",\"prazoDias\":4},{\"nome\":\"Fim\",\"prazoDias\":7,\"finalEtapa\":true}]}").andExpect(status().isCreated()));
        assertThat(novo.get("etapas").get(0).get("prazoDias").isNull()).isTrue();
        assertThat(novo.get("etapas").get(1).get("prazoDias").isNull()).isTrue();
        assertThat(novo.get("etapas").get(2).get("prazoDias").asInt()).isEqualTo(7);
        long fim = f.get("etapas").get(3).get("id").asLong(), analise = f.get("etapas").get(1).get("id").asLong();
        // só a de conclusão aceita prazo; vazio tira o prazo
        alterar(adm, "/api/fluxos/" + f.get("id").asLong() + "/etapas/" + analise + "/dados", "{\"prazoDias\":5,\"mudarPrazo\":true}").andExpect(status().isUnprocessableEntity());
        alterar(adm, "/api/fluxos/" + f.get("id").asLong() + "/etapas/" + fim + "/dados", "{\"prazoDias\":-1,\"mudarPrazo\":true}").andExpect(status().isUnprocessableEntity());
        alterar(adm, "/api/fluxos/" + f.get("id").asLong() + "/etapas/" + fim + "/dados", "{\"prazoDias\":4,\"mudarPrazo\":true}").andExpect(status().isOk());

        // o processo nasce com o prazo para concluir e mover de etapa não o reinicia
        long entrega = corpo(ler(adm, "/api/setores")).get(0).get("entregas").get(0).get("id").asLong();
        long setor = corpo(ler(adm, "/api/setores")).get(0).get("id").asLong();
        long cliente = corpo(enviar(adm, "/api/clientes", "{\"numero\":\"5\",\"nome\":\"Alfa\"}").andExpect(status().isCreated())).get("id").asLong();
        long adminId = 0;
        for (JsonNode u : corpo(ler(adm, "/api/usuarios"))) adminId = u.get("id").asLong();
        long p = corpo(enviar(adm, "/api/processos", "{\"clienteId\":" + cliente + ",\"entregaId\":" + entrega + ",\"setorId\":" + setor + ",\"responsavelId\":" + adminId + "}").andExpect(status().isCreated())).get("id").asLong();
        String esperado = Datas.hoje().plusDays(4).toString();
        assertThat(corpo(ler(adm, "/api/processos/" + p)).get("processo").get("prazoEtapa").asText()).isEqualTo(esperado);
        enviar(adm, "/api/processos/" + p + "/avancar", "{\"etapaId\":" + analise + "}").andExpect(status().isOk());
        assertThat(corpo(ler(adm, "/api/processos/" + p)).get("processo").get("prazoEtapa").asText()).isEqualTo(esperado);   // continua o mesmo
        // quem abre o processo pode escolher a data (dd/mm/aaaa na tela); no passado é recusada
        String data = Datas.hoje().plusDays(30).toString();
        long pData = corpo(enviar(adm, "/api/processos", "{\"clienteId\":" + cliente + ",\"entregaId\":" + entrega + ",\"setorId\":" + setor + ",\"responsavelId\":" + adminId + ",\"prazo\":\"" + data + "\"}").andExpect(status().isCreated())).get("id").asLong();
        assertThat(corpo(ler(adm, "/api/processos/" + pData)).get("processo").get("prazoEtapa").asText()).isEqualTo(data);
        enviar(adm, "/api/processos", "{\"clienteId\":" + cliente + ",\"entregaId\":" + entrega + ",\"setorId\":" + setor + ",\"responsavelId\":" + adminId + ",\"prazo\":\"" + Datas.hoje().minusDays(1) + "\"}").andExpect(status().isUnprocessableEntity());
        // sem prazo de conclusão, o processo não tem prazo (e não atrasa)
        alterar(adm, "/api/fluxos/" + f.get("id").asLong() + "/etapas/" + fim + "/dados", "{\"mudarPrazo\":true}").andExpect(status().isOk());
        long p2 = corpo(enviar(adm, "/api/processos", "{\"clienteId\":" + cliente + ",\"entregaId\":" + entrega + ",\"setorId\":" + setor + ",\"responsavelId\":" + adminId + "}").andExpect(status().isCreated())).get("id").asLong();
        assertThat(corpo(ler(adm, "/api/processos/" + p2)).get("processo").get("prazoEtapa").isNull()).isTrue();
    }

    @Test
    void criarRenomearERemoverEtapas() throws Exception {
        String adm = admin();
        JsonNode f = fluxoPadrao(adm);
        long id = f.get("id").asLong();
        assertThat(nomes(f)).isEqualTo("Abertura|Análise|Revisão|Concluído|");

        // nova etapa vai antes da conclusão por padrão, ou antes de uma etapa indicada
        JsonNode a = corpo(enviar(adm, "/api/fluxos/" + id + "/etapas", "{\"nome\":\"Vistoria\"}").andExpect(status().isCreated()));
        assertThat(nomes(a)).isEqualTo("Abertura|Análise|Revisão|Vistoria|Concluído|");
        long analise = a.get("etapas").get(1).get("id").asLong(), vistoria = a.get("etapas").get(3).get("id").asLong();
        JsonNode b = corpo(enviar(adm, "/api/fluxos/" + id + "/etapas", "{\"nome\":\"Triagem\",\"antesDeEtapaId\":" + analise + "}").andExpect(status().isCreated()));
        assertThat(nomes(b)).isEqualTo("Abertura|Triagem|Análise|Revisão|Vistoria|Concluído|");
        long abertura = b.get("etapas").get(0).get("id").asLong();
        enviar(adm, "/api/fluxos/" + id + "/etapas", "{\"nome\":\"Antes\",\"antesDeEtapaId\":" + abertura + "}").andExpect(status().isUnprocessableEntity());   // nunca antes da inicial
        // as transições seguem a sequência: um processo anda por todas as etapas, inclusive as novas
        long entrega = corpo(ler(adm, "/api/setores")).get(0).get("entregas").get(0).get("id").asLong();
        long setor = corpo(ler(adm, "/api/setores")).get(0).get("id").asLong();
        long cliente = corpo(enviar(adm, "/api/clientes", "{\"numero\":\"5\",\"nome\":\"Alfa\"}").andExpect(status().isCreated())).get("id").asLong();
        long adminId = 0;
        for (JsonNode u : corpo(ler(adm, "/api/usuarios"))) adminId = u.get("id").asLong();
        long p = corpo(enviar(adm, "/api/processos", "{\"clienteId\":" + cliente + ",\"entregaId\":" + entrega + ",\"setorId\":" + setor + ",\"responsavelId\":" + adminId + "}").andExpect(status().isCreated())).get("id").asLong();
        StringBuilder percurso = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            JsonNode det = corpo(ler(adm, "/api/processos/" + p));
            percurso.append(det.get("processo").get("etapaAtual").asText()).append('|');
            if (!det.get("processo").get("status").asText().equals("ABERTO")) break;
            enviar(adm, "/api/processos/" + p + "/avancar", "{\"etapaId\":" + det.get("proximasEtapas").get(0).get("id").asLong() + "}").andExpect(status().isOk());
        }
        assertThat(percurso.toString()).isEqualTo("Abertura|Triagem|Análise|Revisão|Vistoria|Concluído|");

        // renomear; remover só intermediária sem processo em andamento
        alterar(adm, "/api/fluxos/" + id + "/etapas/" + vistoria + "/dados", "{\"nome\":\"Vistoria técnica\"}").andExpect(status().isOk());
        mvc.perform(delete("/api/fluxos/" + id + "/etapas/" + abertura).header("Authorization", "Bearer " + adm)).andExpect(status().isUnprocessableEntity());
        long conclusao = b.get("etapas").get(5).get("id").asLong();
        mvc.perform(delete("/api/fluxos/" + id + "/etapas/" + conclusao).header("Authorization", "Bearer " + adm)).andExpect(status().isUnprocessableEntity());
        long p2 = corpo(enviar(adm, "/api/processos", "{\"clienteId\":" + cliente + ",\"entregaId\":" + entrega + ",\"setorId\":" + setor + ",\"responsavelId\":" + adminId + "}").andExpect(status().isCreated())).get("id").asLong();
        long triagem = b.get("etapas").get(1).get("id").asLong();
        enviar(adm, "/api/processos/" + p2 + "/avancar", "{\"etapaId\":" + triagem + "}").andExpect(status().isOk());
        mvc.perform(delete("/api/fluxos/" + id + "/etapas/" + triagem).header("Authorization", "Bearer " + adm)).andExpect(status().isUnprocessableEntity());   // tem processo nela
        long analise2 = b.get("etapas").get(2).get("id").asLong();
        enviar(adm, "/api/processos/" + p2 + "/avancar", "{\"etapaId\":" + analise2 + "}").andExpect(status().isOk());
        JsonNode depois = corpo(mvc.perform(delete("/api/fluxos/" + id + "/etapas/" + triagem).header("Authorization", "Bearer " + adm)).andExpect(status().isOk()));
        assertThat(nomes(depois)).isEqualTo("Abertura|Análise|Revisão|Vistoria técnica|Concluído|");
        // o processo continua e ainda anda pelas etapas que sobraram
        assertThat(corpo(ler(adm, "/api/processos/" + p2)).get("proximasEtapas").get(0).get("nome").asText()).isEqualTo("Revisão");
    }
}
