package br.com.viriato.relatorio;

import br.com.viriato.processo.StatusProcesso;
import br.com.viriato.seguranca.Contexto;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Qualquer usuário gera relatório, dentro da abrangência do seu papel (ver {@link RelatorioService}). */
@RestController
@RequestMapping("/api/relatorios")
public class RelatorioController {
    private final RelatorioService service;

    public RelatorioController(RelatorioService service) { this.service = service; }

    /** O que o usuário logado pode abranger (a tela usa para configurar os filtros). */
    @GetMapping("/escopo")
    public RelatorioService.Escopo escopo() { return service.escopo(Contexto.atual()); }

    /** Quantos processos entrariam no relatório, antes de gerar o PDF. */
    @GetMapping("/processos/resumo")
    public RelatorioService.Resumo resumo(@RequestParam(required = false) Long usuarioId,
                                          @RequestParam(required = false) Long setorId,
                                          @RequestParam(required = false) Long clienteId,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate,
                                          @RequestParam(defaultValue = "ABERTURA") Referencia referencia,
                                          @RequestParam(required = false) List<StatusProcesso> situacoes) {
        return service.montar(Contexto.atual(), filtro(usuarioId, setorId, clienteId, de, ate, referencia, situacoes)).resumo();
    }

    @GetMapping(value = "/processos/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> pdf(@RequestParam(required = false) Long usuarioId,
                                      @RequestParam(required = false) Long setorId,
                                      @RequestParam(required = false) Long clienteId,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate,
                                      @RequestParam(defaultValue = "ABERTURA") Referencia referencia,
                                      @RequestParam(required = false) List<StatusProcesso> situacoes) {
        var dados = service.montar(Contexto.atual(), filtro(usuarioId, setorId, clienteId, de, ate, referencia, situacoes));
        byte[] pdf = RelatorioPdf.gerar(dados);
        String nome = "relatorio-processos-" + dados.geradoEm().atZone(br.com.viriato.comum.Datas.ZONA)
                .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + ".pdf";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(nome).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(pdf.length)
                .body(pdf);
    }

    private static RelatorioService.Filtro filtro(Long usuarioId, Long setorId, Long clienteId, LocalDate de, LocalDate ate,
                                                  Referencia referencia, List<StatusProcesso> situacoes) {
        Set<StatusProcesso> s = situacoes == null || situacoes.isEmpty() ? EnumSet.noneOf(StatusProcesso.class) : EnumSet.copyOf(situacoes);
        return new RelatorioService.Filtro(usuarioId, setorId, clienteId, de, ate, referencia, s);
    }
}
