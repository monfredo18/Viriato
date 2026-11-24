package br.com.viriato.ged;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Inflater;

/**
 * Leitura básica do texto de um PDF, sem biblioteca: descompacta os fluxos (Flate) e junta as cadeias de texto dos
 * operadores Tj e TJ. Funciona para PDFs comuns com fontes de codificação simples (guias, recibos, extratos); PDFs que só
 * têm imagem (escaneados) ou fontes com codificação própria devolvem pouco ou nenhum texto, e quem chama trata isso como
 * "não deu para ler" (nunca como erro do documento).
 */
final class PdfTexto {
    private static final Pattern FLUXO = Pattern.compile("stream\\r?\\n");
    private static final Pattern LITERAL = Pattern.compile("\\((?:\\\\.|[^\\\\)])*\\)");
    private static final int LIMITE_FLUXO = 8 * 1024 * 1024;

    private PdfTexto() {}

    static String extrair(byte[] pdf) {
        String bruto = new String(pdf, StandardCharsets.ISO_8859_1);
        StringBuilder texto = new StringBuilder();
        Matcher m = FLUXO.matcher(bruto);
        int de = 0;
        while (m.find(de)) {
            int inicio = m.end();
            int fim = bruto.indexOf("endstream", inicio);
            if (fim < 0) break;
            String cabecalho = bruto.substring(Math.max(0, m.start() - 400), m.start());
            byte[] dados = java.util.Arrays.copyOfRange(pdf, inicio, fim);
            String conteudo = cabecalho.contains("FlateDecode") ? descompactar(dados) : new String(dados, StandardCharsets.ISO_8859_1);
            if (conteudo != null) texto.append(textoDoConteudo(conteudo));
            de = fim + 9;
        }
        if (texto.length() == 0) texto.append(textoDoConteudo(bruto));   // PDF sem fluxos compactados
        return texto.toString().replaceAll("\\s+", " ").trim();
    }

    private static String descompactar(byte[] dados) {
        Inflater inf = new Inflater();
        try {
            inf.setInput(dados);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            while (!inf.finished() && out.size() < LIMITE_FLUXO) {
                int n = inf.inflate(buf);
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                out.write(buf, 0, n);
            }
            return out.toString(StandardCharsets.ISO_8859_1);
        } catch (Exception e) {
            return null;
        } finally {
            inf.end();
        }
    }

    /** Só as cadeias que antecedem Tj, TJ, ' ou " dentro de blocos de texto (BT ... ET). */
    private static String textoDoConteudo(String c) {
        StringBuilder out = new StringBuilder();
        int bt = 0;
        while ((bt = c.indexOf("BT", bt)) >= 0) {
            int et = c.indexOf("ET", bt);
            if (et < 0) et = c.length();
            Matcher m = LITERAL.matcher(c.substring(bt, et));
            while (m.find()) out.append(desescapar(m.group().substring(1, m.group().length() - 1)));
            out.append(' ');
            bt = et;
        }
        return out.toString();
    }

    private static String desescapar(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch != '\\' || i + 1 >= s.length()) { b.append(ch); continue; }
            char n = s.charAt(++i);
            switch (n) {
                case 'n' -> b.append('\n');
                case 'r' -> b.append('\r');
                case 't' -> b.append('\t');
                case 'b', 'f' -> { }
                case '(', ')', '\\' -> b.append(n);
                default -> {
                    if (n >= '0' && n <= '7') {
                        int v = n - '0', k = 0;
                        while (k < 2 && i + 1 < s.length() && s.charAt(i + 1) >= '0' && s.charAt(i + 1) <= '7') { v = v * 8 + (s.charAt(++i) - '0'); k++; }
                        b.append((char) v);
                    } else b.append(n);
                }
            }
        }
        return b.toString();
    }
}
