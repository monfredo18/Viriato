package br.com.viriato.automacao;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Preenche {{variaveis}} em assunto e mensagem. Variável desconhecida fica como está, para o erro ser visível. */
public final class ModeloTexto {
    private static final Pattern VARIAVEL = Pattern.compile("\\{\\{\\s*([a-zA-Z]+)\\s*}}");
    /** O que o texto pode usar; a tela mostra esta lista. */
    public static final String VARIAVEIS = "empresa, numero, titulo, entrega, setor, responsavel, etapa, prazo, situacao";

    private ModeloTexto() {}

    public static String aplicar(String modelo, Map<String, String> valores) {
        if (modelo == null) return "";
        Matcher m = VARIAVEL.matcher(modelo);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String v = valores.get(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? m.group() : v));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Assunto de e-mail não pode ter quebra de linha (injeção de cabeçalho). */
    public static String umaLinha(String texto) {
        return texto == null ? "" : texto.replaceAll("[\\r\\n]+", " ").trim();
    }
}
