package br.com.viriato.lgpd;

import br.com.viriato.seguranca.Contexto;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Direitos do titular (LGPD): exportar e anonimizar. Só o administrador, só dentro da própria empresa. */
@RestController
@RequestMapping("/api/lgpd")
@PreAuthorize("hasRole('ADMIN')")
public class LgpdController {
    private final LgpdService service;

    public LgpdController(LgpdService service) { this.service = service; }

    @GetMapping("/usuarios/{id}/exportacao")
    public ResponseEntity<Map<String, Object>> exportarUsuario(@PathVariable Long id) {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, anexo("dados-funcionario-" + id + ".json"))
                .body(service.exportarUsuario(Contexto.atual(), id));
    }

    @GetMapping(value = "/usuarios/{id}/exportacao.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> exportarUsuarioPdf(@PathVariable Long id) {
        byte[] pdf = ExportacaoPdf.gerar("Dados pessoais do funcionário #" + id, service.exportarUsuario(Contexto.atual(), id));
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, anexo("dados-funcionario-" + id + ".pdf")).body(pdf);
    }

    @GetMapping("/clientes/{id}/exportacao")
    public ResponseEntity<Map<String, Object>> exportarCliente(@PathVariable Long id) {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, anexo("dados-empresa-" + id + ".json"))
                .body(service.exportarCliente(Contexto.atual(), id));
    }

    @GetMapping(value = "/clientes/{id}/exportacao.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> exportarClientePdf(@PathVariable Long id) {
        byte[] pdf = ExportacaoPdf.gerar("Dados da empresa atendida #" + id, service.exportarCliente(Contexto.atual(), id));
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, anexo("dados-empresa-" + id + ".pdf")).body(pdf);
    }

    @PostMapping("/usuarios/{id}/anonimizar")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void anonimizarUsuario(@PathVariable Long id) { service.anonimizarUsuario(Contexto.atual(), id); }

    @PostMapping("/clientes/{id}/anonimizar")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void anonimizarCliente(@PathVariable Long id) { service.anonimizarCliente(Contexto.atual(), id); }

    private static String anexo(String nome) { return ContentDisposition.attachment().filename(nome).build().toString(); }
}
