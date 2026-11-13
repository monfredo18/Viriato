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
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Foto de perfil: o tipo vem do conteúdo, cada um troca a própria, a empresa vê, e nada vaza para outra empresa. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FotoPerfilTest {

    static final Map<String, byte[]> ARQUIVOS = new ConcurrentHashMap<>();

    @TestConfiguration
    static class StorageFake {
        @Bean @Primary
        ArmazenamentoArquivos storageEmMemoria() {
            return new ArmazenamentoArquivos() {
                public void salvar(String c, InputStream in, long t, String ct) {
                    try { ARQUIVOS.put(c, in.readAllBytes()); } catch (Exception e) { throw new RuntimeException(e); }
                }
                public InputStream abrir(String c) { return new ByteArrayInputStream(ARQUIVOS.get(c)); }
                public void remover(String c) { ARQUIVOS.remove(c); }
            };
        }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
    static final byte[] JPG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0};

    record Cenario(String admin, String ana, String bia, long anaId, long biaId) {}

    private JsonNode corpo(ResultActions r) throws Exception { return json.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)); }
    private ResultActions foto(String t, byte[] bytes, String nome, String tipo) throws Exception {
        return mvc.perform(multipart("/api/me/foto").file(new MockMultipartFile("arquivo", nome, tipo, bytes)).header("Authorization", "Bearer " + t));
    }
    private ResultActions ler(String t, String url) throws Exception { return mvc.perform(get(url).header("Authorization", "Bearer " + t)); }
    private long criar(String admin, String nome, String papel) throws Exception {
        return corpo(mvc.perform(post("/api/usuarios").header("Authorization", "Bearer " + admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"nome\":\"" + nome + "\",\"senha\":\"senha12345\",\"papel\":\"" + papel + "\"}")).andExpect(status().isCreated())).get("id").asLong();
    }

    private Cenario cenario() throws Exception {
        String s = UUID.randomUUID().toString().substring(0, 8);
        String admin = corpo(mvc.perform(post("/api/auth/registrar-empresa").contentType(MediaType.APPLICATION_JSON)
                .content("{\"empresa\":\"E\",\"nome\":\"Adm " + s + "\",\"email\":\"a-" + s + "@t.com\",\"senha\":\"senha12345\"}")).andExpect(status().isCreated())).get("token").asText();
        long ana = criar(admin, "Ana " + s, "USUARIO");
        long bia = criar(admin, "Bia " + s, "USUARIO");
        return new Cenario(admin, TestesAuth.entrar(mvc, json, "Ana " + s), TestesAuth.entrar(mvc, json, "Bia " + s), ana, bia);
    }

    @Test
    void trocaVeESubstituiAFoto() throws Exception {
        var c = cenario();
        ler(c.ana(), "/api/usuarios/" + c.anaId() + "/foto").andExpect(status().isNotFound());   // ainda sem foto
        assertThat(corpo(ler(c.ana(), "/api/me")).get("fotoVersao").isNull()).isTrue();

        JsonNode dto = corpo(foto(c.ana(), PNG, "eu.png", "image/png").andExpect(status().isOk()));
        assertThat(dto.get("fotoVersao").asLong()).isPositive();
        var resp = ler(c.bia(), "/api/usuarios/" + c.anaId() + "/foto").andExpect(status().isOk()).andReturn().getResponse();   // colega vê
        assertThat(resp.getContentType()).isEqualTo("image/png");
        assertThat(resp.getContentAsByteArray()).isEqualTo(PNG);
        int antes = ARQUIVOS.size();

        // trocar por outra: a antiga some do armazenamento
        foto(c.ana(), JPG, "outra.jpg", "image/jpeg").andExpect(status().isOk());
        assertThat(ARQUIVOS).hasSize(antes);
        assertThat(ler(c.admin(), "/api/usuarios/" + c.anaId() + "/foto").andExpect(status().isOk()).andReturn().getResponse().getContentType()).isEqualTo("image/jpeg");

        // a lista de usuários traz a versão (para o navegador saber quando buscar de novo)
        JsonNode lista = corpo(ler(c.admin(), "/api/usuarios").andExpect(status().isOk()));
        boolean achou = false;
        for (JsonNode u : lista) if (u.get("id").asLong() == c.anaId()) { achou = !u.get("fotoVersao").isNull(); }
        assertThat(achou).isTrue();

        // a pessoa tira a própria foto
        mvc.perform(delete("/api/me/foto").header("Authorization", "Bearer " + c.ana())).andExpect(status().isNoContent());
        ler(c.bia(), "/api/usuarios/" + c.anaId() + "/foto").andExpect(status().isNotFound());
        assertThat(ARQUIVOS).hasSize(antes - 1);
    }

    @Test
    void tipoVemDoConteudoETamanhoELimitado() throws Exception {
        var c = cenario();
        foto(c.ana(), "<script>alert(1)</script>".getBytes(), "eu.png", "image/png").andExpect(status().isUnprocessableEntity());   // nome/tipo mentem
        foto(c.ana(), "%PDF-1.4".getBytes(), "eu.jpg", "image/jpeg").andExpect(status().isUnprocessableEntity());
        foto(c.ana(), new byte[0], "vazio.png", "image/png").andExpect(status().isUnprocessableEntity());
        byte[] grande = new byte[2 * 1024 * 1024 + 1];
        System.arraycopy(PNG, 0, grande, 0, PNG.length);
        foto(c.ana(), grande, "grande.png", "image/png").andExpect(status().isUnprocessableEntity());
        ler(c.bia(), "/api/usuarios/" + c.anaId() + "/foto").andExpect(status().isNotFound());   // nada foi gravado
    }

    @Test
    void soAdministradorTiraAFotoDeOutraPessoaEOutraEmpresaNaoVe() throws Exception {
        var c = cenario();
        foto(c.ana(), PNG, "eu.png", "image/png").andExpect(status().isOk());
        mvc.perform(delete("/api/usuarios/" + c.anaId() + "/foto").header("Authorization", "Bearer " + c.bia())).andExpect(status().isForbidden());
        ler(c.bia(), "/api/usuarios/" + c.anaId() + "/foto").andExpect(status().isOk());

        String o = UUID.randomUUID().toString().substring(0, 8);
        String outra = corpo(mvc.perform(post("/api/auth/registrar-empresa").contentType(MediaType.APPLICATION_JSON)
                .content("{\"empresa\":\"Outra\",\"nome\":\"Adm " + o + "\",\"email\":\"o-" + o + "@t.com\",\"senha\":\"senha12345\"}")).andExpect(status().isCreated())).get("token").asText();
        ler(outra, "/api/usuarios/" + c.anaId() + "/foto").andExpect(status().isNotFound());
        mvc.perform(delete("/api/usuarios/" + c.anaId() + "/foto").header("Authorization", "Bearer " + outra)).andExpect(status().isNotFound());

        mvc.perform(delete("/api/usuarios/" + c.anaId() + "/foto").header("Authorization", "Bearer " + c.admin())).andExpect(status().isNoContent());
        ler(c.bia(), "/api/usuarios/" + c.anaId() + "/foto").andExpect(status().isNotFound());
    }

    @Test
    void apagarAPessoaApagaOArquivoDaFoto() throws Exception {
        var c = cenario();
        foto(c.bia(), JPG, "b.jpg", "image/jpeg").andExpect(status().isOk());
        int antes = ARQUIVOS.size();
        mvc.perform(delete("/api/usuarios/" + c.biaId()).header("Authorization", "Bearer " + c.admin())).andExpect(status().isNoContent());
        assertThat(ARQUIVOS).hasSize(antes - 1);
    }
}
