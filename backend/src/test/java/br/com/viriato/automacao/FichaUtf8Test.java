package br.com.viriato.automacao;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A ficha usa IBM Plex Sans embutida em UTF-8: acentos, travessão e setas saem sem depender da fonte Latin-1 do formato. */
class FichaUtf8Test {
    static byte[] ficha() {
        Map<String, String> v = new LinkedHashMap<>();
        v.put("empresa", "Construções Ágeis & Ação Ltda — ÇÃÕ éüñ łŠ");
        v.put("numero", "1042");
        v.put("titulo", "Laudo técnico — Construções Ágeis");
        v.put("entrega", "Atendimento à população");
        v.put("setor", "Jurídico");
        v.put("responsavel", "João Antônio da Conceição");
        v.put("etapa", "Revisão");
        v.put("prazo", "03/12/2026");
        v.put("situacao", "Em andamento");
        return FichaProcessoPdf.gerar(v, "24/11/2026 10:00",
                List.of(new FichaProcessoPdf.Linha("24/11/2026 10:00", "Movido", "Análise → Revisão: conferência concluída", "Ana Lúcia")));
    }

    @Test
    void fichaEmbuteAFontePlexEmIdentityH() {
        String pdf = new String(ficha(), StandardCharsets.ISO_8859_1);
        assertThat(pdf).contains("IBMPlexSans").contains("Identity-H").contains("/FontFile2");
        assertThat(pdf).doesNotContain("WinAnsiEncoding").doesNotContain("/BaseFont /Helvetica");
    }

    @Test
    void gravaAmostraParaConferenciaVisual() throws IOException {
        Path destino = Path.of("target", "amostra-ficha-utf8.pdf");
        Files.createDirectories(destino.getParent());
        Files.write(destino, ficha());
        assertThat(Files.size(destino)).isGreaterThan(10_000);
    }
}
