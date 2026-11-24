package br.com.viriato.ged;

import br.com.viriato.cadastro.Cliente;
import br.com.viriato.cadastro.ClienteRepository;
import br.com.viriato.crm.Documentos;
import br.com.viriato.documento.ValidadorDeArquivo;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Conferência inteligente de um anexo antes de confirmar: lê o texto (PDF, OFX, XML, CSV) e compara o CNPJ e o período
 * (MM/AAAA) citados com a empresa e a competência do processo. Só avisa: quem decide é a pessoa. Arquivos de imagem,
 * planilhas e .zip não têm o conteúdo lido.
 */
@Component
public class ValidacaoDocumento {
    public enum Nivel { INFO, ALERTA }
    public record Alerta(Nivel nivel, String mensagem) {}

    private static final Pattern CNPJ = Pattern.compile("(?<!\\d)(\\d{2})\\.?(\\d{3})\\.?(\\d{3})/?(\\d{4})-?(\\d{2})(?!\\d)");
    private static final Pattern MES_ANO = Pattern.compile("(?<!\\d)(0[1-9]|1[0-2])/((?:19|20)\\d{2})(?!\\d)");
    private static final Pattern ANO_MES = Pattern.compile("(?<!\\d)((?:19|20)\\d{2})-(0[1-9]|1[0-2])(?!\\d)");
    private static final Pattern OFX = Pattern.compile("DT(?:START|END|POSTED)>?\\s*:?\\s*((?:19|20)\\d{2})(0[1-9]|1[0-2])");

    private final ClienteRepository clientes;

    public ValidacaoDocumento(ClienteRepository clientes) { this.clientes = clientes; }

    public List<Alerta> analisar(String nome, byte[] bytes, Long tenantId, Long clienteId, Integer ano, Integer mes) {
        List<Alerta> out = new ArrayList<>();
        String ext = ValidadorDeArquivo.extensao(nome);
        String texto = switch (ext) {
            case "pdf" -> PdfTexto.extrair(bytes);
            case "xml", "ofx", "csv" -> new String(bytes, StandardCharsets.UTF_8);
            default -> null;
        };
        if (texto == null || texto.strip().length() < 15) {
            out.add(new Alerta(Nivel.INFO, "Não consegui ler o conteúdo deste arquivo (" + (ext.equals("pdf") ? "PDF escaneado ou com codificação própria" : "tipo sem leitura de texto") + "). Confira o CNPJ e o período à mão."));
            return out;
        }
        Cliente c = clienteId == null ? null : clientes.findByIdAndTenantId(clienteId, tenantId).orElse(null);
        if (c != null) {
            Set<String> cnpjs = cnpjsDe(texto);
            if (c.getCnpj() == null) out.add(new Alerta(Nivel.INFO, "A empresa não tem CNPJ cadastrado, então não dá para conferir o CNPJ do documento."));
            else if (cnpjs.isEmpty()) out.add(new Alerta(Nivel.INFO, "Não encontrei nenhum CNPJ no documento para conferir."));
            else if (!cnpjs.contains(c.getCnpj())) {
                out.add(new Alerta(Nivel.ALERTA, "O documento cita " + (cnpjs.size() == 1 ? "o CNPJ " : "os CNPJs ") + String.join(", ", cnpjs.stream().map(Documentos::formatarCnpj).toList())
                        + ", que não " + (cnpjs.size() == 1 ? "é" : "são") + " o da empresa (" + Documentos.formatarCnpj(c.getCnpj()) + ")."));
            }
        }
        if (ano != null && mes != null) {
            Set<String> periodos = periodosDe(texto);
            String esperado = "%02d/%d".formatted(mes, ano);
            if (periodos.isEmpty()) out.add(new Alerta(Nivel.INFO, "Não encontrei um período (MM/AAAA) no documento para conferir com a competência " + esperado + "."));
            else if (!periodos.contains(esperado)) {
                out.add(new Alerta(Nivel.ALERTA, "O documento cita o período " + String.join(", ", periodos) + ", mas a competência deste processo é " + esperado + "."));
            }
        }
        return out;
    }

    static Set<String> cnpjsDe(String texto) {
        Set<String> s = new LinkedHashSet<>();
        Matcher m = CNPJ.matcher(texto);
        while (m.find()) {
            String c = m.group(1) + m.group(2) + m.group(3) + m.group(4) + m.group(5);
            if (Documentos.cnpjValido(c)) s.add(c);
        }
        return s;
    }

    static Set<String> periodosDe(String texto) {
        Set<String> s = new LinkedHashSet<>();
        Matcher a = MES_ANO.matcher(texto);
        while (a.find()) s.add(a.group(1) + "/" + a.group(2));
        Matcher b = ANO_MES.matcher(texto);
        while (b.find()) s.add(b.group(2) + "/" + b.group(1));
        Matcher o = OFX.matcher(texto);
        while (o.find()) s.add(o.group(2) + "/" + o.group(1));
        return s;
    }
}
