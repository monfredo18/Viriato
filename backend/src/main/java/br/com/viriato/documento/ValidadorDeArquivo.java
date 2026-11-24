package br.com.viriato.documento;

import br.com.viriato.comum.NegocioException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Confere o arquivo enviado pelo que ele É, não pelo que o nome ou o Content-Type dizem: a extensão precisa estar na lista
 * permitida ({@code viriato.upload.extensoes}) e o início do conteúdo (os "magic bytes") precisa ser do tipo dessa extensão.
 * Um executável renomeado para .pdf, por exemplo, é recusado. O tipo gravado vem da extensão validada, nunca do cliente.
 */
@Component
public class ValidadorDeArquivo {
    /** Extensões que o sistema sabe conferir e o tipo de conteúdo que cada uma recebe. */
    static final Map<String, String> TIPOS = new LinkedHashMap<>();
    static {
        TIPOS.put("pdf", "application/pdf");
        TIPOS.put("xml", "application/xml");
        TIPOS.put("ofx", "application/x-ofx");
        TIPOS.put("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        TIPOS.put("xls", "application/vnd.ms-excel");
        TIPOS.put("csv", "text/csv");
        TIPOS.put("pfx", "application/x-pkcs12");
        TIPOS.put("p12", "application/x-pkcs12");
        TIPOS.put("jpg", "image/jpeg");
        TIPOS.put("jpeg", "image/jpeg");
        TIPOS.put("png", "image/png");
        TIPOS.put("webp", "image/webp");
        TIPOS.put("zip", "application/zip");
    }

    /** Quantos bytes do início bastam para reconhecer o tipo. */
    public static final int CABECALHO = 4096;

    private final Set<String> permitidas;

    public ValidadorDeArquivo(@Value("${viriato.upload.extensoes:pdf,xml,ofx,xlsx,xls,csv,pfx,p12,jpg,jpeg,png,webp,zip}") String extensoes) {
        this.permitidas = Arrays.stream(extensoes.split(",")).map(s -> s.trim().toLowerCase(Locale.ROOT).replace(".", ""))
                .filter(s -> !s.isEmpty() && TIPOS.containsKey(s)).collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    public Set<String> extensoesPermitidas() { return permitidas; }

    /** Devolve o tipo de conteúdo a gravar, ou lança {@link NegocioException} (HTTP 422) com o motivo. */
    public String validar(String nome, byte[] inicio) {
        String ext = extensao(nome);
        if (ext.isEmpty() || !permitidas.contains(ext)) {
            throw new NegocioException("Tipo de arquivo não permitido" + (ext.isEmpty() ? "" : " (." + ext + ")")
                    + ". Permitidos: " + permitidas.stream().map(e -> "." + e).collect(Collectors.joining(", ")));
        }
        if (inicio == null || inicio.length == 0) throw new NegocioException("Arquivo vazio");
        if (!confere(ext, inicio)) {
            throw new NegocioException("O conteúdo do arquivo não corresponde à extensão ." + ext + ". Envie o arquivo original.");
        }
        return TIPOS.get(ext);
    }

    public static String extensao(String nome) {
        if (nome == null) return "";
        int i = nome.lastIndexOf('.');
        return i < 0 || i == nome.length() - 1 ? "" : nome.substring(i + 1).toLowerCase(Locale.ROOT);
    }

    static boolean confere(String ext, byte[] b) {
        return switch (ext) {
            case "pdf" -> contem(b, "%PDF-".getBytes(), 1024);
            case "png" -> comeca(b, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A);
            case "jpg", "jpeg" -> comeca(b, 0xFF, 0xD8, 0xFF);
            case "webp" -> b.length >= 12 && comeca(b, 'R', 'I', 'F', 'F') && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P';
            case "zip", "xlsx" -> comeca(b, 'P', 'K', 3, 4) || comeca(b, 'P', 'K', 5, 6);
            case "xls" -> comeca(b, 0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1);
            case "pfx", "p12" -> b.length > 4 && (b[0] & 0xFF) == 0x30 && b[1] != 0;   // DER: começa por SEQUENCE
            case "xml" -> textoPuro(b) && primeiroSinal(b) == '<';
            case "csv", "ofx" -> textoPuro(b);
            default -> false;
        };
    }

    private static boolean comeca(byte[] b, int... esperado) {
        if (b.length < esperado.length) return false;
        for (int i = 0; i < esperado.length; i++) if ((b[i] & 0xFF) != esperado[i]) return false;
        return true;
    }

    private static boolean contem(byte[] b, byte[] marca, int ate) {
        int limite = Math.min(b.length, ate) - marca.length;
        outer:
        for (int i = 0; i <= limite; i++) {
            for (int j = 0; j < marca.length; j++) if (b[i + j] != marca[j]) continue outer;
            return true;
        }
        return false;
    }

    /** Texto: sem byte nulo nem caracteres de controle (além de tab, quebra de linha e retorno) no início. */
    private static boolean textoPuro(byte[] b) {
        int n = Math.min(b.length, CABECALHO);
        for (int i = 0; i < n; i++) {
            int c = b[i] & 0xFF;
            if (c == 0 || (c < 32 && c != '\t' && c != '\n' && c != '\r' && c != 12)) return false;
        }
        return true;
    }

    /** Primeiro caractere que não é espaço, ignorando a marca BOM UTF-8. */
    private static int primeiroSinal(byte[] b) {
        int i = comeca(b, 0xEF, 0xBB, 0xBF) ? 3 : 0;
        while (i < b.length && (b[i] == ' ' || b[i] == '\t' || b[i] == '\n' || b[i] == '\r')) i++;
        return i < b.length ? (b[i] & 0xFF) : -1;
    }

    public static List<String> todasAsConhecidas() { return List.copyOf(TIPOS.keySet()); }
}
