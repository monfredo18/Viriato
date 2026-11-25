package br.com.viriato.agente;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JSON mínimo (objetos, listas, texto, números, booleanos e nulo): o agente não precisa de nenhuma biblioteca. */
final class Json {
    private Json() {}

    static Object ler(String texto) {
        Leitor l = new Leitor(texto);
        Object v = l.valor();
        l.espaco();
        if (l.i != texto.length()) throw new IllegalArgumentException("JSON com texto sobrando na posição " + l.i);
        return v;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> objeto(String texto) {
        Object o = ler(texto);
        if (!(o instanceof Map<?, ?>)) throw new IllegalArgumentException("Era esperado um objeto JSON");
        return (Map<String, Object>) o;
    }

    static String escrever(Object v) {
        StringBuilder sb = new StringBuilder();
        escrever(sb, v);
        return sb.toString();
    }

    private static void escrever(StringBuilder sb, Object v) {
        if (v == null) sb.append("null");
        else if (v instanceof String s) texto(sb, s);
        else if (v instanceof Boolean || v instanceof Integer || v instanceof Long) sb.append(v);
        else if (v instanceof Number n) sb.append(n);
        else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean primeiro = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!primeiro) sb.append(',');
                primeiro = false;
                texto(sb, String.valueOf(e.getKey()));
                sb.append(':');
                escrever(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof Iterable<?> it) {
            sb.append('[');
            boolean primeiro = true;
            for (Object o : it) {
                if (!primeiro) sb.append(',');
                primeiro = false;
                escrever(sb, o);
            }
            sb.append(']');
        } else throw new IllegalArgumentException("Tipo sem JSON: " + v.getClass());
    }

    private static void texto(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> { if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c); }
            }
        }
        sb.append('"');
    }

    private static final class Leitor {
        final String t;
        int i;

        Leitor(String t) { this.t = t; }

        void espaco() { while (i < t.length() && Character.isWhitespace(t.charAt(i))) i++; }

        Object valor() {
            espaco();
            if (i >= t.length()) throw new IllegalArgumentException("JSON incompleto");
            char c = t.charAt(i);
            if (c == '{') return objeto();
            if (c == '[') return lista();
            if (c == '"') return texto();
            if (t.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (t.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (t.startsWith("null", i)) { i += 4; return null; }
            return numero();
        }

        Map<String, Object> objeto() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            espaco();
            if (t.charAt(i) == '}') { i++; return m; }
            while (true) {
                espaco();
                String k = texto();
                espaco();
                esperar(':');
                m.put(k, valor());
                espaco();
                if (t.charAt(i) == ',') { i++; continue; }
                esperar('}');
                return m;
            }
        }

        List<Object> lista() {
            List<Object> l = new ArrayList<>();
            i++;
            espaco();
            if (t.charAt(i) == ']') { i++; return l; }
            while (true) {
                l.add(valor());
                espaco();
                if (t.charAt(i) == ',') { i++; continue; }
                esperar(']');
                return l;
            }
        }

        String texto() {
            if (t.charAt(i) != '"') throw new IllegalArgumentException("Era esperado texto na posição " + i);
            i++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (i >= t.length()) throw new IllegalArgumentException("Texto sem fim");
                char c = t.charAt(i++);
                if (c == '"') return sb.toString();
                if (c != '\\') { sb.append(c); continue; }
                char e = t.charAt(i++);
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> { sb.append((char) Integer.parseInt(t.substring(i, i + 4), 16)); i += 4; }
                    default -> sb.append(e);   // \" \\ \/
                }
            }
        }

        Object numero() {
            int ini = i;
            while (i < t.length() && "+-0123456789.eE".indexOf(t.charAt(i)) >= 0) i++;
            if (ini == i) throw new IllegalArgumentException("JSON inválido na posição " + i);
            String n = t.substring(ini, i);
            return n.contains(".") || n.contains("e") || n.contains("E") ? (Object) Double.valueOf(n) : (Object) Long.valueOf(n);
        }

        void esperar(char c) {
            if (i >= t.length() || t.charAt(i) != c) throw new IllegalArgumentException("Era esperado '" + c + "' na posição " + i);
            i++;
        }
    }
}
