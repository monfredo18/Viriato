package br.com.viriato.lgpd;

import br.com.viriato.comum.MarcaPdf;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfWriter;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** PDF da exportação de dados do titular: uma tabela por seção (chave e valor, ou lista de linhas). */
final class ExportacaoPdf {
    private static final Color SUAVE = new Color(0x57, 0x62, 0x79);
    private static final Color LINHA = new Color(0xD3, 0xD9, 0xE4);

    private ExportacaoPdf() {}

    static byte[] gerar(String titulo, Map<String, Object> dados) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Document doc = new Document(PageSize.A4, 40, 40, 58, 50);
            PdfWriter w = PdfWriter.getInstance(doc, out);
            w.setPageEvent(new PdfPageEventHelper() {
                @Override public void onEndPage(PdfWriter writer, Document d) {
                    PdfContentByte cb = writer.getDirectContent();
                    MarcaPdf.faixaTopo(cb, d.getPageSize());
                    cb.beginText();
                    cb.setFontAndSize(MarcaPdf.base(false), 8);
                    cb.setColorFill(SUAVE);
                    cb.showTextAligned(Element.ALIGN_LEFT, "Grupo Viriato — " + titulo, 40, 26, 0);
                    cb.showTextAligned(Element.ALIGN_RIGHT, "Página " + writer.getPageNumber(), d.getPageSize().getWidth() - 40, 26, 0);
                    cb.endText();
                }
            });
            doc.addTitle(titulo);
            doc.addAuthor("Viriato");
            doc.open();
            doc.add(new Paragraph(titulo, MarcaPdf.fonte(17, Font.BOLD, MarcaPdf.MARINHO)));
            Paragraph sub = new Paragraph("Gerado em " + dados.get("geradoEm") + ". " + dados.get("base"), MarcaPdf.fonte(8.5f, Font.NORMAL, SUAVE));
            sub.setSpacingAfter(10);
            doc.add(sub);
            for (var e : dados.entrySet()) {
                if (List.of("titular", "titularId", "geradoEm", "base").contains(e.getKey())) continue;
                Paragraph h = new Paragraph(rotulo(e.getKey()), MarcaPdf.fonte(12, Font.BOLD, MarcaPdf.MARINHO));
                h.setSpacingBefore(10);
                h.setSpacingAfter(4);
                doc.add(h);
                doc.add(secao(e.getValue()));
            }
            doc.close();
            return out.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException("Falha ao gerar o PDF da exportação", ex);
        }
    }

    @SuppressWarnings("unchecked")
    private static PdfPTable secao(Object valor) {
        if (valor instanceof Map<?, ?> m) {
            PdfPTable t = new PdfPTable(new float[]{1.2f, 3f});
            t.setWidthPercentage(100);
            ((Map<String, Object>) m).forEach((k, v) -> { t.addCell(celula(rotulo(k), true)); t.addCell(celula(texto(v), false)); });
            return t;
        }
        List<Map<String, Object>> linhas = (List<Map<String, Object>>) valor;
        if (linhas.isEmpty()) {
            PdfPTable vazio = new PdfPTable(1);
            vazio.setWidthPercentage(100);
            vazio.addCell(celula("Nenhum registro.", false));
            return vazio;
        }
        List<String> colunas = new ArrayList<>(linhas.get(0).keySet());
        PdfPTable t = new PdfPTable(colunas.size());
        t.setWidthPercentage(100);
        t.setHeaderRows(1);
        for (String c : colunas) t.addCell(celula(rotulo(c), true));
        for (Map<String, Object> l : linhas) for (String c : colunas) t.addCell(celula(texto(l.get(c)), false));
        return t;
    }

    private static PdfPCell celula(String texto, boolean cabecalho) {
        PdfPCell c = new PdfPCell(new Phrase(texto, MarcaPdf.fonte(8.5f, cabecalho ? Font.BOLD : Font.NORMAL, cabecalho ? SUAVE : Color.BLACK)));
        c.setBorderColor(LINHA);
        c.setBorder(Rectangle.BOX);
        c.setPadding(4);
        if (cabecalho) c.setBackgroundColor(new Color(0xF3, 0xF5, 0xFA));
        return c;
    }

    private static String texto(Object v) { return v == null ? "—" : String.valueOf(v); }

    private static String rotulo(String chave) {
        String s = chave.replaceAll("([A-Z])", " $1").replace('_', ' ').toLowerCase();
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
