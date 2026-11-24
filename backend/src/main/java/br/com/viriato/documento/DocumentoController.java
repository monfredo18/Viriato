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
                                                @RequestParam(value = "requisitoId", required = false) Long requisitoId,
                                                @RequestParam(value = "guardaLegal", defaultValue = "false") boolean guardaLegal,
                                                @RequestParam(value = "categoria", required = false) String categoria) throws IOException {
        return service.enviar(Contexto.atual(), processoId, arquivo, requisitoId, guardaLegal, categoria);
    }

    /** Confere o arquivo (conteúdo, CNPJ e período) sem gravar: a tela mostra os avisos antes de a pessoa confirmar o envio. */
    @PostMapping(value = "/processos/{processoId}/documentos/verificar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public List<br.com.viriato.ged.ValidacaoDocumento.Alerta> verificar(@PathVariable Long processoId, @RequestParam("arquivo") MultipartFile arquivo) throws IOException {
        return service.verificar(Contexto.atual(), processoId, arquivo);
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

    @PostMapping("/documentos/{id}/arquivar")
    public DocumentoService.DocumentoDto arquivar(@PathVariable Long id) { return service.arquivar(Contexto.atual(), id, true); }

    @PostMapping("/documentos/{id}/desarquivar")
    public DocumentoService.DocumentoDto desarquivar(@PathVariable Long id) { return service.arquivar(Contexto.atual(), id, false); }

    @DeleteMapping("/documentos/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remover(@PathVariable Long id) { service.remover(Contexto.atual(), id); }
}
