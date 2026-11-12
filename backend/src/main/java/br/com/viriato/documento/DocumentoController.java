package br.com.viriato.documento;

import br.com.viriato.seguranca.Contexto;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class DocumentoController {
    private final DocumentoService service;

    public DocumentoController(DocumentoService service) { this.service = service; }

    @GetMapping("/processos/{processoId}/documentos")
    public List<DocumentoService.DocumentoDto> listar(@PathVariable Long processoId) {
        return service.listar(Contexto.atual(), processoId);
    }

    @PostMapping(value = "/processos/{processoId}/documentos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentoService.DocumentoDto enviar(@PathVariable Long processoId,
                                                @RequestParam("arquivo") MultipartFile arquivo,
                                                @RequestParam(value = "requisitoId", required = false) Long requisitoId) throws IOException {
        return service.enviar(Contexto.atual(), processoId, arquivo, requisitoId);
    }

    /** O download passa pelo backend: valida tenant e permissão antes de entregar o arquivo. */
    @GetMapping("/documentos/{id}/download")
    public ResponseEntity<InputStreamResource> baixar(@PathVariable Long id) {
        var a = service.baixar(Contexto.atual(), id);
        var d = a.documento();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(d.getNomeOriginal(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(d.getContentType()))
                .contentLength(d.getTamanho())
                .body(new InputStreamResource(a.conteudo()));
    }

    @DeleteMapping("/documentos/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remover(@PathVariable Long id) { service.remover(Contexto.atual(), id); }
}
