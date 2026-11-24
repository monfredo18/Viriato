package br.com.viriato;

import static br.com.viriato.Crm.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** Ficha fiscal e societária: CNPJ, regime, sócios, contatos, checklists e isolamento. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(Crm.StorageFake.class)
class ClienteFiscalTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    static final String FISCAL = "{\"cnpj\":\"11.222.333/0001-81\",\"razaoSocial\":\"Construtora Alfa Ltda\",\"nomeFantasia\":\"Alfa\",\"inscricaoEstadual\":\"123456\","
            + "\"inscricaoMunicipal\":\"987\",\"cnaePrincipal\":\"4120-4/00\",\"cnaePrincipalDescricao\":\"Construção de edifícios\","
            + "\"cnaesSecundarios\":[{\"codigo\":\"4399-1/03\",\"descricao\":\"Obras de alvenaria\"}],\"regimeTributario\":\"LUCRO_PRESUMIDO\",\"uf\":\"sp\","
            + "\"codigoMunicipioIbge\":\"3550308\",\"dataAberturaEmpresa\":\"2015-03-10\"}";

    @Test
    void cadastraComDadosFiscaisEMostraNaFicha() throws Exception {
        Empresa e = empresa(mvc, json);
        long id = cliente(mvc, json, e.gestor(), "10", "Alfa " + e.s(), FISCAL);
        JsonNode f = corpo(json, ler(mvc, e.gestor(), "/api/clientes/" + id + "/ficha").andExpect(status().isOk()));
        JsonNode fi = f.get("fiscal");
        assertThat(fi.get("cnpj").asText()).isEqualTo("11222333000181");
        assertThat(fi.get("cnpjFormatado").asText()).isEqualTo("11.222.333/0001-81");
        assertThat(fi.get("regimeTributario").asText()).isEqualTo("LUCRO_PRESUMIDO");
        assertThat(fi.get("uf").asText()).isEqualTo("SP");
        assertThat(fi.get("cnaePrincipal").asText()).isEqualTo("4120400");
        assertThat(fi.get("cnaesSecundarios").get(0).get("codigo").asText()).isEqualTo("4399103");
        assertThat(fi.get("dataAberturaEmpresa").asText()).isEqualTo("2015-03-10");
        assertThat(fi.get("statusContratual").asText()).isEqualTo("ONBOARDING");   // empresa nova entra em onboarding
        // a lista de empresas já mostra o resumo fiscal
        JsonNode lista = corpo(json, ler(mvc, e.usuario(), "/api/clientes").andExpect(status().isOk()));
        assertThat(lista.toString()).contains("11.222.333/0001-81").contains("LUCRO_PRESUMIDO");
    }

    @Test
    void validaCnpjUfIbgeCnaeERegime() throws Exception {
        Empresa e = empresa(mvc, json);
        long id = cliente(mvc, json, e.admin(), "11", "Beta " + e.s(), null);
        String url = "/api/clientes/" + id;
        for (String ruim : new String[]{"{\"cnpj\":\"11222333000182\"}", "{\"uf\":\"XX\"}", "{\"codigoMunicipioIbge\":\"123\"}", "{\"cnaePrincipal\":\"123\"}", "{\"regimeTributario\":\"QUALQUER\"}"}) {
            alterar(mvc, e.admin(), url, "{\"fiscal\":" + ruim + "}").andExpect(status().is4xxClientError());
        }
        alterar(mvc, e.admin(), url, "{\"fiscal\":{\"cnpj\":\"11222333000181\",\"regimeTributario\":\"SIMPLES_NACIONAL\"}}").andExpect(status().isOk());
        alterar(mvc, e.admin(), url, "{\"fiscal\":{\"cnpj\":\"\"}}").andExpect(status().isOk());   // em branco apaga
        assertThat(corpo(json, ler(mvc, e.admin(), url + "/ficha")).get("fiscal").get("cnpj").isNull()).isTrue();
    }

    @Test
    void cnpjEhUnicoPorEmpresaDoSistemaMasPodeRepetirEmOutraEmpresa() throws Exception {
        Empresa e = empresa(mvc, json);
        cliente(mvc, json, e.admin(), "12", "Gama " + e.s(), "{\"cnpj\":\"11222333000181\"}");
        String r = corpo(json, enviar(mvc, e.admin(), "/api/clientes", "{\"numero\":\"13\",\"nome\":\"Delta " + e.s() + "\",\"fiscal\":{\"cnpj\":\"11.222.333/0001-81\"}}").andExpect(status().isUnprocessableEntity())).get("erro").asText();
        assertThat(r).contains("já está cadastrado").contains("Gama");
        Empresa outra = empresa(mvc, json);
        cliente(mvc, json, outra.admin(), "12", "Gama " + outra.s(), "{\"cnpj\":\"11222333000181\"}");   // outra empresa do sistema: pode
    }

    @Test
    void soAdministradorEGestorVeemAFichaEOutraEmpresaNaoEnxerga() throws Exception {
        Empresa e = empresa(mvc, json);
        long id = cliente(mvc, json, e.admin(), "14", "Épsilon " + e.s(), FISCAL.replace("11.222.333/0001-81", "45.723.174/0001-10"));
        ler(mvc, e.usuario(), "/api/clientes/" + id + "/ficha").andExpect(status().isForbidden());
        enviar(mvc, e.usuario(), "/api/clientes/" + id + "/socios", "{\"nome\":\"X\"}").andExpect(status().isForbidden());
        ler(mvc, e.gestor(), "/api/clientes/" + id + "/ficha").andExpect(status().isOk());
        Empresa outra = empresa(mvc, json);
        ler(mvc, outra.admin(), "/api/clientes/" + id + "/ficha").andExpect(status().isNotFound());
        enviar(mvc, outra.admin(), "/api/clientes/" + id + "/contatos", "{\"nome\":\"X\",\"area\":\"FISCAL\"}").andExpect(status().isNotFound());
        alterar(mvc, outra.admin(), "/api/clientes/" + id, "{\"fiscal\":{\"uf\":\"RJ\"}}").andExpect(status().isNotFound());
    }

    @Test
    void sociosGuardamCpfSoMascaradoEAsomaNaoPassaDeCem() throws Exception {
        Empresa e = empresa(mvc, json);
        long id = cliente(mvc, json, e.gestor(), "15", "Zeta " + e.s(), null);
        String base = "/api/clientes/" + id + "/socios";
        JsonNode s1 = corpo(json, enviar(mvc, e.gestor(), base, "{\"nome\":\"Maria Souza\",\"cpf\":\"529.982.247-25\",\"percentual\":60,\"responsavelLegal\":true,\"email\":\"maria@x.com\",\"telefone\":\"11 99999-0000\"}").andExpect(status().isCreated()));
        assertThat(s1.get("cpf").asText()).isEqualTo("***.982.247-**");
        assertThat(s1.toString()).doesNotContain("52998224725");
        enviar(mvc, e.gestor(), base, "{\"nome\":\"João\",\"cpf\":\"11111111111\"}").andExpect(status().isUnprocessableEntity());      // CPF inválido
        enviar(mvc, e.gestor(), base, "{\"nome\":\"João\",\"percentual\":50}").andExpect(status().isUnprocessableEntity());            // 60 + 50 > 100
        enviar(mvc, e.gestor(), base, "{\"nome\":\"João\",\"percentual\":40.00}").andExpect(status().isCreated());
        JsonNode ficha = corpo(json, ler(mvc, e.gestor(), "/api/clientes/" + id + "/ficha"));
        assertThat(ficha.get("socios")).hasSize(2);
        assertThat(ficha.toString()).doesNotContain("52998224725");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(base + "/" + s1.get("id").asLong()).header("Authorization", "Bearer " + e.gestor())).andExpect(status().isNoContent());
    }

    @Test
    void contatosPorAreaComFlagsDeGuiaECobranca() throws Exception {
        Empresa e = empresa(mvc, json);
        long id = cliente(mvc, json, e.gestor(), "16", "Eta " + e.s(), null);
        String base = "/api/clientes/" + id + "/contatos";
        enviar(mvc, e.gestor(), base, "{\"nome\":\"Paulo\"}").andExpect(status().isUnprocessableEntity());   // sem área
        enviar(mvc, e.gestor(), base, "{\"nome\":\"Paulo\",\"area\":\"RH\"}").andExpect(status().isBadRequest());
        enviar(mvc, e.gestor(), base, "{\"nome\":\"Paulo\",\"area\":\"FISCAL\",\"email\":\"errado\"}").andExpect(status().isUnprocessableEntity());
        JsonNode c = corpo(json, enviar(mvc, e.gestor(), base, "{\"nome\":\"Paulo\",\"area\":\"FINANCEIRO\",\"email\":\"p@x.com\",\"recebeGuias\":true,\"recebeCobrancas\":true}").andExpect(status().isCreated()));
        assertThat(c.get("recebeGuias").asBoolean()).isTrue();
        JsonNode alt = corpo(json, alterar(mvc, e.gestor(), base + "/" + c.get("id").asLong(), "{\"area\":\"DP\",\"recebeCobrancas\":false}").andExpect(status().isOk()));
        assertThat(alt.get("area").asText()).isEqualTo("DP");
        assertThat(alt.get("recebeCobrancas").asBoolean()).isFalse();
        assertThat(alt.get("recebeGuias").asBoolean()).isTrue();   // não mexido
    }

    @Test
    void onboardingNasceComAEmpresaEConcluirTudoAtiva() throws Exception {
        Empresa e = empresa(mvc, json);
        long id = cliente(mvc, json, e.gestor(), "17", "Teta " + e.s(), null);
        JsonNode itens = corpo(json, ler(mvc, e.gestor(), "/api/clientes/" + id + "/checklist").andExpect(status().isOk()));
        assertThat(itens).hasSize(7);
        assertThat(itens.get(0).get("tipo").asText()).isEqualTo("ONBOARDING");
        for (JsonNode i : itens) alterar(mvc, e.gestor(), "/api/clientes/" + id + "/checklist/" + i.get("id").asLong(), "{\"concluido\":true}").andExpect(status().isOk());
        assertThat(corpo(json, ler(mvc, e.gestor(), "/api/clientes/" + id + "/ficha")).get("fiscal").get("statusContratual").asText()).isEqualTo("ATIVO");
        assertThat(corpo(json, ler(mvc, e.admin(), "/api/auditoria")).toString()).contains("EMPRESA_ATIVADA");
    }

    @Test
    void offboardingNasceAoIniciarOEncerramentoEConcluirEncerra() throws Exception {
        Empresa e = empresa(mvc, json);
        long id = cliente(mvc, json, e.gestor(), "18", "Iota " + e.s(), null);
        alterar(mvc, e.gestor(), "/api/clientes/" + id, "{\"fiscal\":{\"statusContratual\":\"OFFBOARDING\"}}").andExpect(status().isOk());
        alterar(mvc, e.gestor(), "/api/clientes/" + id, "{\"fiscal\":{\"statusContratual\":\"OFFBOARDING\"}}").andExpect(status().isOk());   // repetir não duplica
        JsonNode todos = corpo(json, ler(mvc, e.gestor(), "/api/clientes/" + id + "/checklist"));
        long off = 0;
        for (JsonNode i : todos) if (i.get("tipo").asText().equals("OFFBOARDING")) off++;
        assertThat(off).isEqualTo(6);
        for (JsonNode i : todos) if (i.get("tipo").asText().equals("OFFBOARDING")) alterar(mvc, e.gestor(), "/api/clientes/" + id + "/checklist/" + i.get("id").asLong(), "{\"concluido\":true}").andExpect(status().isOk());
        JsonNode f = corpo(json, ler(mvc, e.gestor(), "/api/clientes/" + id + "/ficha"));
        assertThat(f.get("fiscal").get("statusContratual").asText()).isEqualTo("ENCERRADO");
        assertThat(f.get("ativo").asBoolean()).isFalse();
    }
}
