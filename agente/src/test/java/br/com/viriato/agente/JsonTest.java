package br.com.viriato.agente;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

@SuppressWarnings("unchecked")
class JsonTest {
    @Test
    void leObjetosListasTextoNumerosEBooleanos() {
        Map<String, Object> m = Json.objeto(" {\"a\": 1, \"b\": [true, false, null, 2.5, \"x\"], \"c\": {\"d\": \"e\\n\\\"f\\\" \\u00e7\"}, \"g\": -7} ");
        assertThat(m.get("a")).isEqualTo(1L);
        assertThat(m.get("g")).isEqualTo(-7L);
        assertThat((List<Object>) m.get("b")).containsExactly(true, false, null, 2.5, "x");
        assertThat(((Map<?, ?>) m.get("c")).get("d")).isEqualTo("e\n\"f\" ç");
        assertThat(Json.objeto("{}")).isEmpty();
        assertThat(Json.ler("[]")).isEqualTo(List.of());
    }

    @Test
    void escreveELeDeVolta() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("texto", "linha\ncom \"aspas\", barra \\ e acento ç\u0001");
        m.put("numero", 42);
        m.put("lista", List.of("a", 1, true));
        m.put("nulo", null);
        Map<String, Object> volta = Json.objeto(Json.escrever(m));
        assertThat(volta.get("texto")).isEqualTo("linha\ncom \"aspas\", barra \\ e acento ç\u0001");
        assertThat(volta.get("numero")).isEqualTo(42L);
        assertThat((List<Object>) volta.get("lista")).containsExactly("a", 1L, true);
        assertThat(volta).containsKey("nulo");
        assertThat(volta.get("nulo")).isNull();
    }

    @Test
    void recusaJsonInvalido() {
        for (String ruim : new String[]{"", "{", "{\"a\"}", "[1,", "{\"a\":1} lixo", "\"sem fim", "nada"}) {
            assertThatThrownBy(() -> Json.ler(ruim)).as(ruim).isInstanceOf(RuntimeException.class);
        }
        assertThatThrownBy(() -> Json.objeto("[1]")).isInstanceOf(IllegalArgumentException.class);
    }
}
