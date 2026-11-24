package br.com.viriato.automacao;

import br.com.viriato.comum.MarcaPdf;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfTemplate;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import java.awt.Color;
import java.io.IOException;
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

    private static Font f(float tamanho, int estilo, Color cor) { return MarcaPdf.fonte(tamanho, estilo, cor); }

    static byte[] gerar(Map<String, String> v, String geradoEm, List<Linha> historico) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Document doc = new Document(PageSize.A4, 48, 48, 58, 56);
            PdfWriter writer = PdfWriter.getInstance(doc, out);
            writer.setPageEvent(new Pagina(v.get("numero")));
            doc.addTitle("Ficha do processo " + v.get("numero"));
            doc.addAuthor("Viriato");
            doc.open();

            PdfPTable topo = new PdfPTable(new float[]{1.4f, 3.6f});
            topo.setWidthPercentage(100);
            PdfPCell logo = new PdfPCell(MarcaPdf.logo(92, 62), false);
            logo.setBorder(Rectangle.NO_BORDER);
            logo.setVerticalAlignment(Element.ALIGN_MIDDLE);
            topo.addCell(logo);
            PdfPCell texto = new PdfPCell();
            texto.setBorder(Rectangle.NO_BORDER);
            texto.setVerticalAlignment(Element.ALIGN_MIDDLE);
            texto.setPaddingLeft(8);
            texto.addElement(new Paragraph("Ficha do processo " + v.get("numero"), f(19, Font.BOLD, MARINHO)));
            Paragraph sub = new Paragraph(v.get("titulo"), f(10.5f, Font.NORMAL, SUAVE));
            sub.setSpacingBefore(2);
            texto.addElement(sub);
            topo.addCell(texto);
            topo.setSpacingAfter(14);
            doc.add(topo);

            PdfPTable dados = new PdfPTable(new float[]{1.1f, 2.9f});
            dados.setWidthPercentage(100);
            for (String[] par : new String[][]{
                    {"Empresa", v.get("empresa")}, {"Entrega", v.get("entrega")}, {"Setor", v.get("setor")},
                    {"Responsável", v.get("responsavel")}, {"Etapa", v.get("etapa")}, {"Prazo para concluir", v.get("prazo")},
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

    /** Faixa no topo e rodapé com a numeração "Página X de Y" em cada página. */
    private static final class Pagina extends PdfPageEventHelper {
        private final String numero;
        private PdfTemplate total;
        private BaseFont base;

        Pagina(String numero) { this.numero = numero; }

        @Override
        public void onOpenDocument(PdfWriter writer, Document document) {
            try {
                base = MarcaPdf.base(false);
            } catch (IllegalStateException e) {
                throw e;
            }
            total = writer.getDirectContent().createTemplate(30, 12);
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            PdfContentByte cb = writer.getDirectContent();
            MarcaPdf.faixaTopo(cb, document.getPageSize());
            float esq = document.leftMargin(), dir = document.getPageSize().getWidth() - document.rightMargin();
            cb.saveState();
            cb.setColorStroke(BORDA);
            cb.setLineWidth(0.6f);
            cb.moveTo(esq, 40);
            cb.lineTo(dir, 40);
            cb.stroke();
            cb.beginText();
            cb.setFontAndSize(base, 8);
            cb.setColorFill(SUAVE);
            cb.setTextMatrix(esq, 28);
            cb.showText("Grupo Viriato — Ficha do processo " + numero);
            String pagina = "Página " + writer.getPageNumber() + " de ";
            float x = dir - base.getWidthPoint(pagina, 8) - 14;
            cb.setTextMatrix(x, 28);
            cb.showText(pagina);
            cb.endText();
            cb.addTemplate(total, x + base.getWidthPoint(pagina, 8), 28);
            cb.restoreState();
        }

        @Override
        public void onCloseDocument(PdfWriter writer, Document document) {
            total.beginText();
            total.setFontAndSize(base, 8);
            total.setColorFill(SUAVE);
            total.setTextMatrix(0, 0);
            total.showText(String.valueOf(writer.getPageNumber() - 1));
            total.endText();
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
