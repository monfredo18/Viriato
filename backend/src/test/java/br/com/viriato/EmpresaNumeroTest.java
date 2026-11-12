package br.com.viriato;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

/** Empresa atendida com nome e número; o número do processo é o número da empresa. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EmpresaNumeroTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    record Cenario(String admin, String gestor, long adminId, long setorId, long entregaId) {}

    private JsonNode corpo(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private ResultActions enviar(String token, String url, String body) throws Exception {
        return mvc.perform(post(url).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions alterar(String token, String url, String body) throws Exception {
        return mvc.perform(patch(url).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private JsonNode consultar(String token, String url) throws Exception {
        return corpo(mvc.perform(get(url).header("Authorization", "Bearer " + token)).andExpect(status().isOk()));
    }

    private Cenario cenario() throws Exception {
        String sfx = UUID.randomUUID().toString();
        var reg = corpo(mvc.perform(post("/api/auth/registrar-empresa").contentType(MediaType.APPLICATION_JSON)
                .content("{\"empresa\":\"Emp\",\"nome\":\"Adm\",\"email\":\"adm-" + sfx + "@t.com\",\"senha\":\"senha12345\"}"))
                .andExpect(status().isCreated()));
        String admin = reg.get("token").asText();
        enviar(admin, "/api/usuarios", "{\"nome\":\"Gestor\",\"email\":\"ges-" + sfx + "@t.com\",\"senha\":\"senha12345\",\"papel\":\"GESTOR\"}").andExpect(status().isCreated());
        String gestor = corpo(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"ges-" + sfx + "@t.com\",\"senha\":\"senha12345\"}")).andExpect(status().isOk())).get("token").asText();
        JsonNode geral = consultar(admin, "/api/setores").get(0);
        return new Cenario(admin, gestor, reg.get("usuario").get("id").asLong(), geral.get("id").asLong(),
                geral.get("entregas").get(0).get("id").asLong());
    }

    private long processo(Cenario c, long clienteId) throws Exception {
        return corpo(enviar(c.admin(), "/api/processos", "{\"clienteId\":" + clienteId + ",\"entregaId\":" + c.entregaId()
                + ",\"setorId\":" + c.setorId() + ",\"responsavelId\":" + c.adminId() + "}").andExpect(status().isCreated())).get("id").asLong();
    }

    private long empresa(Cenario c, String numero, String nome) throws Exception {
        return corpo(enviar(c.admin(), "/api/clientes", "{\"numero\":\"" + numero + "\",\"nome\":\"" + nome + "\"}")
                .andExpect(status().isCreated())).get("id").asLong();
    }

    @Test
    void cadastroExigeNomeENumero_numeroSoComDigitos_eNaoSeRepete() throws Exception {
        Cenario c = cenario();
        var ok = corpo(enviar(c.gestor(), "/api/clientes", "{\"numero\":\"1042\",\"nome\":\"Construtora Alfa\"}").andExpect(status().isCreated()));
        assertThat(ok.get("numero").asText()).isEqualTo("1042");
        assertThat(ok.get("nome").asText()).isEqualTo("Construtora Alfa");

        enviar(c.admin(), "/api/clientes", "{\"nome\":\"Sem número\"}").andExpect(status().isBadRequest());
        enviar(c.admin(), "/api/clientes", "{\"numero\":\"\",\"nome\":\"Vazio\"}").andExpect(status().isBadRequest());
        enviar(c.admin(), "/api/clientes", "{\"numero\":\"10A2\",\"nome\":\"Letras\"}").andExpect(status().isBadRequest());
        enviar(c.admin(), "/api/clientes", "{\"numero\":\"12345678901\",\"nome\":\"Comprido\"}").andExpect(status().isBadRequest());
        enviar(c.admin(), "/api/clientes", "{\"numero\":\"2000\"}").andExpect(status().isBadRequest());           // sem nome

        var repetido = corpo(enviar(c.admin(), "/api/clientes", "{\"numero\":\"1042\",\"nome\":\"Outra empresa\"}").andExpect(status().isUnprocessableEntity()));
        assertThat(repetido.get("erro").asText()).contains("1042");
        assertThat(consultar(c.admin(), "/api/clientes")).hasSize(1);
    }

    @Test
    void numeroDoProcessoEOnumeroDaEmpresa_processosDaMesmaEmpresaCompartilham() throws Exception {
        Cenario c = cenario();
        long alfa = empresa(c, "1042", "Alfa");
        long beta = empresa(c, "2077", "Beta");
        long p1 = processo(c, alfa);
        long p2 = processo(c, alfa);
        long p3 = processo(c, beta);

        assertThat(consultar(c.admin(), "/api/processos/" + p1).get("processo").get("numero").asText()).isEqualTo("1042");
        assertThat(consultar(c.admin(), "/api/processos/" + p2).get("processo").get("numero").asText()).isEqualTo("1042");
        assertThat(consultar(c.admin(), "/api/processos/" + p3).get("processo").get("numero").asText()).isEqualTo("2077");
        assertThat(p1).isNotEqualTo(p2);                                                   // continuam sendo processos distintos

        assertThat(consultar(c.admin(), "/api/processos?busca=1042").get("content")).hasSize(2);   // busca pelo número
        assertThat(consultar(c.admin(), "/api/processos?busca=2077").get("content")).hasSize(1);
    }

    @Test
    void editarONumeroDaEmpresaAtualizaOsProcessosDela_enumeroRepetidoERecusado() throws Exception {
        Cenario c = cenario();
        long alfa = empresa(c, "1042", "Alfa");
        long beta = empresa(c, "2077", "Beta");
        long p1 = processo(c, alfa);
        long p2 = processo(c, beta);

        alterar(c.gestor(), "/api/clientes/" + alfa, "{\"numero\":\"1043\"}").andExpect(status().isOk());
        assertThat(consultar(c.admin(), "/api/processos/" + p1).get("processo").get("numero").asText()).isEqualTo("1043");
        assertThat(consultar(c.admin(), "/api/processos/" + p2).get("processo").get("numero").asText()).isEqualTo("2077");   // outra empresa não muda

        alterar(c.admin(), "/api/clientes/" + alfa, "{\"numero\":\"2077\"}").andExpect(status().isUnprocessableEntity());      // já é da Beta
        alterar(c.admin(), "/api/clientes/" + alfa, "{\"numero\":\"abc\"}").andExpect(status().isBadRequest());
        alterar(c.admin(), "/api/clientes/" + alfa, "{\"numero\":\"1043\",\"nome\":\"Alfa Renomeada\"}").andExpect(status().isOk());   // o próprio número é permitido
    }

    @Test
    void processoEncerradoMantemONumeroDepoisQueAEmpresaEApagada() throws Exception {
        Cenario c = cenario();
        long alfa = empresa(c, "1042", "Alfa");
        long p = processo(c, alfa);
        enviar(c.admin(), "/api/processos/" + p + "/cancelar", "{\"motivo\":\"x\"}").andExpect(status().isOk());
        mvc.perform(delete("/api/clientes/" + alfa).header("Authorization", "Bearer " + c.admin())).andExpect(status().isNoContent());

        JsonNode depois = consultar(c.admin(), "/api/processos/" + p).get("processo");
        assertThat(depois.get("clienteNome").isNull()).isTrue();
        assertThat(depois.get("numero").asText()).isEqualTo("1042");                       // o número fica registrado no processo
        empresa(c, "1042", "Nova empresa com o mesmo número");                              // e o número volta a ficar livre
    }

    @Test
    void numerosDeEmpresasNaoSeMisturamEntreEmpresasDoSistema() throws Exception {
        Cenario a = cenario();
        Cenario b = cenario();
        empresa(a, "1042", "Alfa da A");
        empresa(b, "1042", "Alfa da B");                                                    // mesmo número em outra empresa do sistema: permitido
        assertThat(consultar(a.admin(), "/api/clientes")).hasSize(1);
        assertThat(consultar(b.admin(), "/api/clientes")).hasSize(1);
    }
}
