package br.com.viriato.comum;

import com.lowagie.text.BadElementException;
import com.lowagie.text.Image;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfContentByte;
import java.awt.Color;
import java.io.IOException;
import java.io.InputStream;

/** Identidade visual dos PDFs gerados pelo sistema: logo do Grupo Viriato e a faixa no topo de cada página. */
public final class MarcaPdf {
    public static final Color MARINHO = new Color(0x14, 0x20, 0x4A);
    public static final Color OURO = new Color(0xC9, 0xA2, 0x27);

    private static byte[] logo;

    private MarcaPdf() {}

    /** A logo (imagem sem fundo), encaixada na largura/altura máximas em pontos. */
    public static synchronized Image logo(float larguraMax, float alturaMax) {
        try {
            if (logo == null) {
                try (InputStream in = MarcaPdf.class.getResourceAsStream("/branding/logo-viriato.png")) {
                    if (in == null) throw new IllegalStateException("Logo do Viriato não encontrada");
                    logo = in.readAllBytes();
                }
            }
            Image img = Image.getInstance(logo);
            img.scaleToFit(larguraMax, alturaMax);
            return img;
        } catch (BadElementException | IOException e) {
            throw new IllegalStateException("Falha ao carregar a logo do Viriato", e);
        }
    }

    /** Faixa marinho com filete dourado no alto da página. */
    public static void faixaTopo(PdfContentByte cb, Rectangle pagina) {
        cb.saveState();
        cb.setColorFill(MARINHO);
        cb.rectangle(0, pagina.getHeight() - 10, pagina.getWidth(), 10);
        cb.fill();
        cb.setColorFill(OURO);
        cb.rectangle(0, pagina.getHeight() - 13, pagina.getWidth(), 3);
        cb.fill();
        cb.restoreState();
    }
}
