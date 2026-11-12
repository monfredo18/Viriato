package br.com.viriato.relatorio;

import br.com.viriato.comum.Datas;
import br.com.viriato.relatorio.RelatorioService.Dados;
import br.com.viriato.relatorio.RelatorioService.Linha;
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
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfTemplate;
import com.lowagie.text.pdf.PdfWriter;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.format.DateTimeFormatter;

/** Gera o PDF do relatório de processos (A4 paisagem), na identidade visual do sistema. */
public final class RelatorioPdf {
    private static final Color MARINHO = new Color(0x14, 0x20, 0x4A);
    private static final Color AZUL = new Color(0x2B, 0x59, 0xC3);
    private static final Color OURO = new Color(0xC9, 0xA2, 0x27);
    private static final Color OURO_ESCURO = new Color(0x7A, 0x5C, 0x05);
    private static final Color VERMELHO = new Color(0xE1, 0x1D, 0x2E);
    private static final Color TEXTO = new Color(0x10, 0x18, 0x28);
    private static final Color SUAVE = new Color(0x57, 0x62, 0x79);
    private static final Color LINHA = new Color(0xD3, 0xD9, 0xE4);
    private static final Color ZEBRA = new Color(0xF3, 0xF5, 0xFA);

    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATA_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private RelatorioPdf() {}

    private static Font f(float tamanho, int estilo, Color cor) {
        return FontFactory.getFont(FontFactory.HELVETICA, tamanho, estilo, cor);
    }

    public static byte[] gerar(Dados d) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Document doc = new Document(new Rectangle(PageSize.A4.getHeight(), PageSize.A4.getWidth()), 36, 36, 36, 52);   // A4 paisagem
            PdfWriter writer = PdfWriter.getInstance(doc, out);
            writer.setPageEvent(new Rodape(d));
            doc.addTitle("Relatório de processos");
            doc.addAuthor("Viriato");
            doc.open();

            cabecalho(doc, d);
            resumo(doc, d);
            tabela(doc, d);

            doc.close();
            return out.toByteArray();
        } catch (DocumentException e) {
            throw new IllegalStateException("Falha ao gerar o PDF do relatório", e);
        }
    }

    // ------------------------------------------------------------ blocos

    private static void cabecalho(Document doc, Dados d) throws DocumentException {
        Paragraph marca = new Paragraph("V I R I A T O", f(9, Font.BOLD, SUAVE));
        marca.setSpacingAfter(2);
        doc.add(marca);

        Paragraph titulo = new Paragraph("Relatório de processos", f(20, Font.BOLD, MARINHO));
        doc.add(titulo);

        PdfPTable regua = new PdfPTable(new float[]{1, 11});          // filete dourado curto sob o título
        regua.setWidthPercentage(100);
        regua.setSpacingBefore(3);
        regua.setSpacingAfter(8);
        PdfPCell ouro = new PdfPCell(new Phrase(" ", f(1, Font.NORMAL, OURO)));
        ouro.setFixedHeight(3);
        ouro.setBorder(Rectangle.NO_BORDER);
        ouro.setBackgroundColor(OURO);
        regua.addCell(ouro);
        PdfPCell vazio = new PdfPCell(new Phrase(" ", f(1, Font.NORMAL, OURO)));
        vazio.setBorder(Rectangle.NO_BORDER);
        regua.addCell(vazio);
        doc.add(regua);

        PdfPTable meta = new PdfPTable(new float[]{1.1f, 5, 1.1f, 5});
        meta.setWidthPercentage(100);
        meta.setSpacingAfter(4);
        linhaMeta(meta, "Empresa", d.nomeDoSistema(), "Gerado por", d.geradoPor());
        linhaMeta(meta, "Abrangência", d.abrangencia(), "Gerado em", d.geradoEm().atZone(Datas.ZONA).format(DATA_HORA));
        doc.add(meta);

        String filtros = d.filtros().isEmpty() ? "Nenhum (todos os processos da abrangência)" : String.join("  |  ", d.filtros());
        Paragraph pf = new Paragraph();
        pf.add(new com.lowagie.text.Chunk("FILTROS  ", f(7.5f, Font.BOLD, SUAVE)));
        pf.add(new com.lowagie.text.Chunk(filtros, f(9, Font.NORMAL, TEXTO)));
        pf.setSpacingAfter(10);
        doc.add(pf);
    }

    private static void linhaMeta(PdfPTable t, String r1, String v1, String r2, String v2) {
        t.addCell(celulaMeta(r1.toUpperCase(), f(7.5f, Font.BOLD, SUAVE)));
        t.addCell(celulaMeta(v1, f(9.5f, Font.NORMAL, TEXTO)));
        t.addCell(celulaMeta(r2.toUpperCase(), f(7.5f, Font.BOLD, SUAVE)));
        t.addCell(celulaMeta(v2, f(9.5f, Font.NORMAL, TEXTO)));
    }

    private static PdfPCell celulaMeta(String texto, Font fonte) {
        PdfPCell c = new PdfPCell(new Phrase(texto, fonte));
        c.setBorder(Rectangle.NO_BORDER);
        c.setPaddingTop(2);
        c.setPaddingBottom(2);
        c.setPaddingLeft(0);
        return c;
    }

    private static void resumo(Document doc, Dados d) throws DocumentException {
        PdfPTable t = new PdfPTable(5);
        t.setWidthPercentage(100);
        t.setSpacingAfter(12);
        var r = d.resumo();
        caixa(t, "Total", r.total(), MARINHO);
        caixa(t, "Em andamento", r.emAndamento(), AZUL);
        caixa(t, "Atrasados", r.atrasados(), VERMELHO);
        caixa(t, "Concluídos", r.concluidos(), OURO_ESCURO);
        caixa(t, "Cancelados", r.cancelados(), SUAVE);
        doc.add(t);
    }

    private static void caixa(PdfPTable t, String rotulo, int valor, Color cor) {
        Paragraph numero = new Paragraph(String.valueOf(valor), f(18, Font.BOLD, cor));
        numero.setLeading(20f);
        Paragraph legenda = new Paragraph(rotulo.toUpperCase(), f(7.5f, Font.BOLD, SUAVE));
        legenda.setSpacingBefore(3);
        PdfPCell c = new PdfPCell();
        c.addElement(numero);
        c.addElement(legenda);
        c.setBorder(Rectangle.LEFT);
        c.setBorderWidthLeft(3);
        c.setBorderColorLeft(cor);
        c.setPaddingLeft(8);
        c.setPaddingTop(2);
        c.setPaddingBottom(6);
        t.addCell(c);
    }

    private static void tabela(Document doc, Dados d) throws DocumentException {
        if (d.linhas().isEmpty()) {
            Paragraph vazio = new Paragraph("Nenhum processo encontrado com os filtros selecionados.", f(11, Font.ITALIC, SUAVE));
            vazio.setSpacingBefore(18);
            doc.add(vazio);
            return;
        }
        PdfPTable t = new PdfPTable(new float[]{7, 18, 11, 17, 14, 10, 11, 12});
        t.setWidthPercentage(100);
        t.setHeaderRows(1);                 // o cabeçalho se repete em cada página
        t.setSplitLate(false);
        for (String h : new String[]{"Número", "Empresa", "Setor", "Entrega", "Responsável", "Aberto em", "Encerrado em", "Situação"}) {
            PdfPCell c = new PdfPCell(new Phrase(h.toUpperCase(), f(7.5f, Font.BOLD, Color.WHITE)));
            c.setBackgroundColor(MARINHO);
            c.setBorder(Rectangle.NO_BORDER);
            c.setPadding(5);
            t.addCell(c);
        }
        boolean zebra = false;
        for (Linha l : d.linhas()) {
            Color fundo = zebra ? ZEBRA : Color.WHITE;
            t.addCell(corpo(l.numero(), f(8.5f, Font.BOLD, MARINHO), fundo));
            t.addCell(corpo(l.empresa(), f(8.5f, Font.NORMAL, TEXTO), fundo));
            t.addCell(corpo(l.setor(), f(8.5f, Font.NORMAL, TEXTO), fundo));
            t.addCell(corpo(l.entrega(), f(8.5f, Font.NORMAL, TEXTO), fundo));
            t.addCell(corpo(l.responsavel(), f(8.5f, Font.NORMAL, TEXTO), fundo));
            t.addCell(corpo(l.abertura().format(DATA), f(8.5f, Font.NORMAL, TEXTO), fundo));
            t.addCell(corpo(l.encerramento() == null ? "—" : l.encerramento().format(DATA), f(8.5f, Font.NORMAL, TEXTO), fundo));
            t.addCell(corpo(l.situacao().toUpperCase(), f(7.5f, Font.BOLD, corDaSituacao(l)), fundo));
            zebra = !zebra;
        }
        doc.add(t);
    }

    private static Color corDaSituacao(Linha l) {
        if (l.atrasado()) return VERMELHO;
        return switch (l.situacao()) {
            case "Concluído" -> OURO_ESCURO;
            case "Cancelado" -> SUAVE;
            default -> AZUL;
        };
    }

    private static PdfPCell corpo(String texto, Font fonte, Color fundo) {
        PdfPCell c = new PdfPCell(new Phrase(texto, fonte));
        c.setBackgroundColor(fundo);
        c.setBorder(Rectangle.BOTTOM);
        c.setBorderColorBottom(LINHA);
        c.setBorderWidthBottom(0.5f);
        c.setPaddingTop(4);
        c.setPaddingBottom(5);
        c.setPaddingLeft(5);
        c.setPaddingRight(4);
        c.setVerticalAlignment(Element.ALIGN_MIDDLE);
        return c;
    }

    // ------------------------------------------------------------ rodapé "Página X de Y"

    private static final class Rodape extends PdfPageEventHelper {
        private final Dados dados;
        private PdfTemplate total;
        private BaseFont base;

        Rodape(Dados dados) { this.dados = dados; }

        @Override
        public void onOpenDocument(PdfWriter writer, Document document) {
            try {
                base = BaseFont.createFont(BaseFont.HELVETICA, BaseFont.WINANSI, false);
            } catch (DocumentException | IOException e) {
                throw new IllegalStateException(e);
            }
            total = writer.getDirectContent().createTemplate(30, 12);
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            PdfContentByte cb = writer.getDirectContent();
            float esquerda = document.leftMargin();
            float direita = document.getPageSize().getWidth() - document.rightMargin();
            cb.saveState();
            cb.setColorStroke(LINHA);
            cb.setLineWidth(0.6f);
            cb.moveTo(esquerda, 36);
            cb.lineTo(direita, 36);
            cb.stroke();

            cb.beginText();
            cb.setFontAndSize(base, 8);
            cb.setColorFill(SUAVE);
            cb.setTextMatrix(esquerda, 24);
            cb.showText("Viriato — Relatório de processos — " + dados.nomeDoSistema() + " — "
                    + dados.geradoEm().atZone(Datas.ZONA).format(DATA_HORA));
            String pagina = "Página " + writer.getPageNumber() + " de ";
            float largura = base.getWidthPoint(pagina, 8);
            float x = direita - largura - 14;
            cb.setTextMatrix(x, 24);
            cb.showText(pagina);
            cb.endText();
            cb.addTemplate(total, x + largura, 24);
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
}
