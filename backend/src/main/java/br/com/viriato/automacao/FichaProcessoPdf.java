package br.com.viriato.automacao;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

/** PDF "Ficha do processo": dados do processo e o histórico até o momento em que a regra rodou. */
final class FichaProcessoPdf {
    private static final Color MARINHO = new Color(0x14, 0x20, 0x4A);
    private static final Color SUAVE = new Color(0x57, 0x62, 0x79);
    private static final Color BORDA = new Color(0xD3, 0xD9, 0xE4);

    record Linha(String quando, String acao, String detalhe, String autor) {}

    private FichaProcessoPdf() {}

    private static Font f(float tamanho, int estilo, Color cor) { return FontFactory.getFont(FontFactory.HELVETICA, tamanho, estilo, cor); }

    static byte[] gerar(Map<String, String> v, String geradoEm, List<Linha> historico) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Document doc = new Document(PageSize.A4, 48, 48, 48, 48);
            PdfWriter.getInstance(doc, out);
            doc.addTitle("Ficha do processo " + v.get("numero"));
            doc.addAuthor("Viriato");
            doc.open();

            Paragraph marca = new Paragraph("V I R I A T O", f(9, Font.BOLD, SUAVE));
            marca.setSpacingAfter(2);
            doc.add(marca);
            Paragraph titulo = new Paragraph("Ficha do processo " + v.get("numero"), f(20, Font.BOLD, MARINHO));
            titulo.setSpacingAfter(2);
            doc.add(titulo);
            Paragraph sub = new Paragraph(v.get("titulo"), f(11, Font.NORMAL, SUAVE));
            sub.setSpacingAfter(14);
            doc.add(sub);

            PdfPTable dados = new PdfPTable(new float[]{1.1f, 2.9f});
            dados.setWidthPercentage(100);
            for (String[] par : new String[][]{
                    {"Empresa", v.get("empresa")}, {"Entrega", v.get("entrega")}, {"Setor", v.get("setor")},
                    {"Responsável", v.get("responsavel")}, {"Etapa", v.get("etapa")}, {"Prazo da etapa", v.get("prazo")},
                    {"Situação", v.get("situacao")}}) {
                dados.addCell(celula(par[0], f(9, Font.BOLD, SUAVE), false));
                dados.addCell(celula(par[1], f(10.5f, Font.NORMAL, Color.BLACK), false));
            }
            doc.add(dados);

            Paragraph hist = new Paragraph("Histórico", f(11, Font.BOLD, MARINHO));
            hist.setSpacingBefore(18);
            hist.setSpacingAfter(6);
            doc.add(hist);
            PdfPTable t = new PdfPTable(new float[]{1.3f, 1.2f, 3f, 1.4f});
            t.setWidthPercentage(100);
            t.setHeaderRows(1);
            for (String h : new String[]{"Quando", "Ação", "Detalhe", "Por"}) t.addCell(celula(h, f(8.5f, Font.BOLD, Color.WHITE), true));
            for (Linha l : historico) {
                t.addCell(celula(l.quando(), f(8.5f, Font.NORMAL, Color.BLACK), false));
                t.addCell(celula(l.acao(), f(8.5f, Font.NORMAL, Color.BLACK), false));
                t.addCell(celula(l.detalhe(), f(8.5f, Font.NORMAL, Color.BLACK), false));
                t.addCell(celula(l.autor(), f(8.5f, Font.NORMAL, Color.BLACK), false));
            }
            doc.add(t);

            Paragraph rodape = new Paragraph("Gerado automaticamente em " + geradoEm, f(8, Font.NORMAL, SUAVE));
            rodape.setSpacingBefore(14);
            rodape.setAlignment(Element.ALIGN_RIGHT);
            doc.add(rodape);
            doc.close();
            return out.toByteArray();
        } catch (DocumentException e) {
            throw new IllegalStateException("Falha ao gerar a ficha do processo", e);
        }
    }

    private static PdfPCell celula(String texto, Font fonte, boolean cabecalho) {
        PdfPCell c = new PdfPCell(new Phrase(texto == null ? "—" : texto, fonte));
        c.setPadding(5);
        c.setBorderColor(BORDA);
        c.setBorderWidth(0.6f);
        if (cabecalho) c.setBackgroundColor(MARINHO);
        return c;
    }
}
