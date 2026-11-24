package br.com.viriato;

import static br.com.viriato.Crm.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Fluxos não lineares (reordenar, transições livres, retorno) e campos extras tipados por entrega. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(Crm.StorageFake.class)
class FluxoECamposTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private ResultActions pedir(String t, String url, String corpo) throws Exception {
        return mvc.perform(put(url).header("Authorization", "Bearer " + t).contentType(MediaType.APPLICATION_JSON).content(corpo));
    }

    private JsonNode fluxo(String t, long id) throws Exception { return corpo(json, ler(mvc, t, "/api/fluxos/" + id).andExpect(status().isOk())); }
    private long etapa(JsonNode f, String nome) { for (JsonNode e : f.get("etapas")) if (e.get("nome").asText().equals(nome)) return e.get("id").asLong(); throw new AssertionError(nome); }
    private static Set<String> arestas(JsonNode f) { Set<String> s = new HashSet<>(); for (JsonNode t : f.get("transicoes")) s.add(t.get("de").asLong() + ">" + t.get("para").asLong()); return s; }
    private static String t(long de, long para) { return "{\"de\":" + de + ",\"para\":" + para + "}"; }

    @Test
    void reordenarEtapasIntermediariasEmFluxoEmSequencia() throws Exception {
        Empresa e = empresa(mvc, json);
        long fid = corpo(json, ler(mvc, e.admin(), "/api/fluxos")).get(0).get("id").asLong();
        JsonNode f = fluxo(e.admin(), fid);
        long ab = etapa(f, "Abertura"), an = etapa(f, "Análise"), rev = etapa(f, "Revisão"), fim = etapa(f, "Concluído");

        pedir(e.admin(), "/api/fluxos/" + fid + "/ordem", "{\"etapaIds\":[" + an + "]}").andExpect(status().isUnprocessableEntity());                  // falta uma
        pedir(e.admin(), "/api/fluxos/" + fid + "/ordem", "{\"etapaIds\":[" + ab + "," + rev + "]}").andExpect(status().isUnprocessableEntity());       // a inicial não entra
        pedir(e.usuario(), "/api/fluxos/" + fid + "/ordem", "{\"etapaIds\":[" + rev + "," + an + "]}").andExpect(status().isForbidden());
        JsonNode novo = corpo(json, pedir(e.gestor(), "/api/fluxos/" + fid + "/ordem", "{\"etapaIds\":[" + rev + "," + an + "]}").andExpect(status().isOk()));
        assertThat(novo.get("etapas").get(1).get("nome").asText()).isEqualTo("Revisão");
        assertThat(novo.get("etapas").get(2).get("nome").asText()).isEqualTo("Análise");
        assertThat(novo.get("etapas").get(3).get("nome").asText()).isEqualTo("Concluído");
        assertThat(arestas(novo)).containsExactlyInAnyOrder(ab + ">" + rev, rev + ">" + an, an + ">" + fim);   // sequência religada
    }

    @Test
    void transicoesNaoLinearesRamificacaoERetorno() throws Exception {
        Empresa e = empresa(mvc, json);
        JsonNode entrega = corpo(json, ler(mvc, e.admin(), "/api/entregas")).get(0);
        long fid = entrega.get("fluxoId").asLong(), entregaId = entrega.get("id").asLong();
        JsonNode f = fluxo(e.admin(), fid);
        long ab = etapa(f, "Abertura"), an = etapa(f, "Análise"), rev = etapa(f, "Revisão"), fim = etapa(f, "Concluído");
        String url = "/api/fluxos/" + fid + "/transicoes";

        pedir(e.admin(), url, "{\"transicoes\":[" + t(ab, an) + "," + t(an, an) + "," + t(an, rev) + "," + t(rev, fim) + "]}").andExpect(status().isUnprocessableEntity());      // laço
        pedir(e.admin(), url, "{\"transicoes\":[" + t(ab, an) + "," + t(an, rev) + "," + t(rev, fim) + "," + t(fim, an) + "]}").andExpect(status().isUnprocessableEntity());   // saída da conclusão
        pedir(e.admin(), url, "{\"transicoes\":[" + t(ab, an) + "," + t(an, rev) + "," + t(rev, fim) + "," + t(rev, ab) + "]}").andExpect(status().isUnprocessableEntity());    // volta à inicial
        pedir(e.admin(), url, "{\"transicoes\":[" + t(ab, an) + "," + t(rev, fim) + "]}").andExpect(status().isUnprocessableEntity());                                          // Revisão inalcançável
        pedir(e.admin(), url, "{\"transicoes\":[" + t(ab, an) + "," + t(an, rev) + "]}").andExpect(status().isUnprocessableEntity());                                            // ninguém chega ao fim
        pedir(e.admin(), url, "{\"transicoes\":[" + t(ab, an) + "," + t(an, rev) + "," + t(rev, fim) + "," + t(999999, fim) + "]}").andExpect(status().isUnprocessableEntity());   // etapa de outro fluxo

        // Revisão pode seguir para Concluído OU voltar para Análise; Análise também pode ir direto a Concluído
        JsonNode ok = corpo(json, pedir(e.gestor(), url, "{\"transicoes\":[" + t(ab, an) + "," + t(an, rev) + "," + t(an, fim) + "," + t(rev, fim) + "," + t(rev, an) + "," + t(rev, an) + "]}").andExpect(status().isOk()));
        assertThat(arestas(ok)).containsExactlyInAnyOrder(ab + ">" + an, an + ">" + rev, an + ">" + fim, rev + ">" + fim, rev + ">" + an);

        // um processo percorre o caminho não linear
        long cliente = cliente(mvc, json, e.gestor(), "70", "Fluxo " + e.s(), null);
        long eu = corpo(json, ler(mvc, e.admin(), "/api/me")).get("id").asLong();
        long p = corpo(json, enviar(mvc, e.admin(), "/api/processos", "{\"clienteId\":" + cliente + ",\"entregaId\":" + entregaId + ",\"setorId\":" + e.setorId() + ",\"responsavelId\":" + eu + "}").andExpect(status().isCreated())).get("id").asLong();
        String av = "/api/processos/" + p + "/avancar";
        enviar(mvc, e.admin(), av, "{\"etapaId\":" + fim + "}").andExpect(status().isUnprocessableEntity());   // Abertura não vai direto ao fim
        enviar(mvc, e.admin(), av, "{\"etapaId\":" + an + "}").andExpect(status().isOk());
        enviar(mvc, e.admin(), av, "{\"etapaId\":" + rev + "}").andExpect(status().isOk());
        enviar(mvc, e.admin(), av, "{\"etapaId\":" + an + "}").andExpect(status().isOk());                    // reprovou: volta para Análise
        JsonNode proximas = corpo(json, ler(mvc, e.admin(), "/api/processos/" + p)).get("proximasEtapas");
        assertThat(proximas).hasSize(2);                                                                        // Revisão ou direto ao Concluído
        enviar(mvc, e.admin(), av, "{\"etapaId\":" + fim + "}").andExpect(status().isOk());
        assertThat(corpo(json, ler(mvc, e.admin(), "/api/processos/" + p)).get("processo").get("status").asText()).isEqualTo("CONCLUIDO");
    }

    @Test
    void adicionarERemoverEtapaMantemAsTransicoesPersonalizadas() throws Exception {
        Empresa e = empresa(mvc, json);
        long fid = corpo(json, ler(mvc, e.admin(), "/api/fluxos")).get(0).get("id").asLong();
        JsonNode f = fluxo(e.admin(), fid);
        long ab = etapa(f, "Abertura"), an = etapa(f, "Análise"), rev = etapa(f, "Revisão"), fim = etapa(f, "Concluído");
        pedir(e.admin(), "/api/fluxos/" + fid + "/transicoes", "{\"transicoes\":[" + t(ab, an) + "," + t(an, rev) + "," + t(rev, fim) + "," + t(rev, an) + "]}").andExpect(status().isOk());

        JsonNode com = corpo(json, enviar(mvc, e.admin(), "/api/fluxos/" + fid + "/etapas", "{\"nome\":\"Conferência\"}").andExpect(status().isCreated()));
        long conf = etapa(com, "Conferência");
        assertThat(arestas(com)).contains(ab + ">" + an, an + ">" + rev, rev + ">" + fim, rev + ">" + an);          // as transições de antes continuam (incluindo o retorno)
        assertThat(arestas(com)).contains(rev + ">" + conf, conf + ">" + fim);                                         // a nova fica entre a anterior e a conclusão
        assertThat(com.get("etapas").get(3).get("nome").asText()).isEqualTo("Conferência");

        JsonNode sem = corpo(json, mvc.perform(delete("/api/fluxos/" + fid + "/etapas/" + conf).header("Authorization", "Bearer " + e.admin())).andExpect(status().isOk()));
        assertThat(arestas(sem)).containsExactlyInAnyOrder(ab + ">" + an, an + ">" + rev, rev + ">" + fim, rev + ">" + an);   // quem entrava nela passou a entrar no destino
    }

    // ---------- campos extras ----------

    private static final String CAMPOS = "[{\"rotulo\":\"Valor do tributo\",\"tipo\":\"MOEDA\",\"obrigatorio\":true},{\"rotulo\":\"Data de protocolo\",\"tipo\":\"DATA\"},"
            + "{\"rotulo\":\"Quantidade de notas\",\"tipo\":\"NUMERO\"},{\"rotulo\":\"Situação da guia\",\"tipo\":\"SELECAO\",\"opcoes\":[\"Pendente\",\"Entregue\"]},{\"rotulo\":\"Observação do analista\",\"tipo\":\"TEXTO\"}]";

    @Test
    void esquemaDeCamposTipadosPorEntrega() throws Exception {
        Empresa e = empresa(mvc, json);
        long entrega = corpo(json, ler(mvc, e.admin(), "/api/entregas")).get(0).get("id").asLong();
        String url = "/api/entregas/" + entrega + "/campos";
        pedir(e.usuario(), url, CAMPOS).andExpect(status().isForbidden());
        pedir(e.gestor(), url, "[{\"rotulo\":\"X\",\"tipo\":\"SELECAO\",\"opcoes\":[\"so uma\"]}]").andExpect(status().isUnprocessableEntity());
        pedir(e.gestor(), url, "[{\"rotulo\":\"Igual\",\"tipo\":\"TEXTO\"},{\"rotulo\":\"igual\",\"tipo\":\"TEXTO\"}]").andExpect(status().isUnprocessableEntity());
        pedir(e.gestor(), url, "[{\"rotulo\":\"Sem tipo\"}]").andExpect(status().isUnprocessableEntity());
        JsonNode def = corpo(json, pedir(e.gestor(), url, CAMPOS).andExpect(status().isOk()));
        assertThat(def).hasSize(5);
        assertThat(def.get(0).get("chave").asText()).isEqualTo("valor_do_tributo");
        assertThat(def.get(3).get("opcoes")).hasSize(2);
        assertThat(corpo(json, ler(mvc, e.usuario(), url).andExpect(status().isOk()))).hasSize(5);                 // todos veem o esquema da entrega que enxergam
        Empresa outra = empresa(mvc, json);
        ler(mvc, outra.admin(), url).andExpect(status().isNotFound());
        pedir(outra.admin(), url, CAMPOS).andExpect(status().isNotFound());
    }

    @Test
    void preencherCamposValidaPeloTipoERegistraNoHistoricoEObrigatorioTravaAConclusao() throws Exception {
        Empresa e = empresa(mvc, json);
        JsonNode entrega = corpo(json, ler(mvc, e.admin(), "/api/entregas")).get(0);
        long entregaId = entrega.get("id").asLong();
        pedir(e.gestor(), "/api/entregas/" + entregaId + "/campos", CAMPOS).andExpect(status().isOk());
        long cliente = cliente(mvc, json, e.gestor(), "71", "Campos " + e.s(), null);
        long usuarioId = corpo(json, ler(mvc, e.usuario(), "/api/me")).get("id").asLong();
        long p = corpo(json, enviar(mvc, e.admin(), "/api/processos", "{\"clienteId\":" + cliente + ",\"entregaId\":" + entregaId + ",\"setorId\":" + e.setorId() + ",\"responsavelId\":" + usuarioId
                + ",\"camposExtras\":{\"quantidade_de_notas\":12}}").andExpect(status().isCreated())).get("id").asLong();
        String url = "/api/processos/" + p + "/campos";
        JsonNode detalhe = corpo(json, ler(mvc, e.usuario(), "/api/processos/" + p));
        assertThat(detalhe.get("campos")).hasSize(5);                                                              // o detalhe traz o esquema para a tela
        assertThat(detalhe.get("processo").get("camposExtras").get("quantidade_de_notas").asInt()).isEqualTo(12);
        enviar(mvc, e.admin(), "/api/processos", "{\"clienteId\":" + cliente + ",\"entregaId\":" + entregaId + ",\"setorId\":" + e.setorId() + ",\"responsavelId\":" + usuarioId + ",\"camposExtras\":{\"situacao_da_guia\":\"Talvez\"}}").andExpect(status().isUnprocessableEntity());

        for (String ruim : new String[]{"{\"valores\":{\"inexistente\":1}}", "{\"valores\":{\"data_de_protocolo\":\"31/12/2026\"}}", "{\"valores\":{\"situacao_da_guia\":\"Talvez\"}}", "{\"valores\":{\"quantidade_de_notas\":\"abc\"}}"}) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(url).header("Authorization", "Bearer " + e.usuario()).contentType(MediaType.APPLICATION_JSON).content(ruim)).andExpect(status().isUnprocessableEntity());
        }
        JsonNode ok = corpo(json, mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(url).header("Authorization", "Bearer " + e.usuario()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"valores\":{\"data_de_protocolo\":\"2026-11-10\",\"situacao_da_guia\":\"Entregue\",\"observacao_do_analista\":\"  tudo certo  \",\"valor_do_tributo\":\"1.234,56\"}}")).andExpect(status().isOk()));
        JsonNode extras = ok.get("camposExtras");
        assertThat(extras.get("data_de_protocolo").asText()).isEqualTo("2026-11-10");
        assertThat(extras.get("observacao_do_analista").asText()).isEqualTo("tudo certo");
        assertThat(extras.get("valor_do_tributo").decimalValue()).isEqualByComparingTo("1234.56");
        assertThat(extras.get("quantidade_de_notas").asInt()).isEqualTo(12);                                       // o que não foi enviado fica
        assertThat(corpo(json, ler(mvc, e.admin(), "/api/processos/" + p)).get("historico").toString()).contains("Campos atualizados");

        // vazio apaga; sem o obrigatório não conclui
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(url).header("Authorization", "Bearer " + e.admin()).contentType(MediaType.APPLICATION_JSON).content("{\"valores\":{\"valor_do_tributo\":\"\"}}")).andExpect(status().isOk());
        for (int i = 0; i < 2; i++) {
            long prox = corpo(json, ler(mvc, e.admin(), "/api/processos/" + p)).get("proximasEtapas").get(0).get("id").asLong();
            enviar(mvc, e.admin(), "/api/processos/" + p + "/avancar", "{\"etapaId\":" + prox + "}").andExpect(status().isOk());
        }
        long ultima = corpo(json, ler(mvc, e.admin(), "/api/processos/" + p)).get("proximasEtapas").get(0).get("id").asLong();
        String erro = corpo(json, enviar(mvc, e.admin(), "/api/processos/" + p + "/avancar", "{\"etapaId\":" + ultima + "}").andExpect(status().isUnprocessableEntity())).get("erro").asText();
        assertThat(erro).contains("campos obrigatórios").contains("Valor do tributo");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(url).header("Authorization", "Bearer " + e.admin()).contentType(MediaType.APPLICATION_JSON).content("{\"valores\":{\"valor_do_tributo\":500}}")).andExpect(status().isOk());
        enviar(mvc, e.admin(), "/api/processos/" + p + "/avancar", "{\"etapaId\":" + ultima + "}").andExpect(status().isOk());
    }
}
