package br.com.viriato.usuario;

import br.com.viriato.seguranca.Contexto;
import br.com.viriato.seguranca.UsuarioLogado;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** Foto de perfil: cada pessoa troca ou tira a própria; qualquer pessoa da empresa vê; o administrador pode tirar a de outra. */
@RestController
@RequestMapping("/api")
public class FotoController {
    private final FotoService fotos;

    public FotoController(FotoService fotos) { this.fotos = fotos; }

    @PostMapping(value = "/me/foto", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public UsuarioDto trocar(@RequestPart("arquivo") MultipartFile arquivo) throws IOException {
        return fotos.trocar(Contexto.atual(), arquivo);
    }

    @DeleteMapping("/me/foto")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void tirar() {
        UsuarioLogado quem = Contexto.atual();
        fotos.remover(quem.id(), quem.tenantId());
    }

    @DeleteMapping("/usuarios/{id}/foto")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void tirarDeOutra(@PathVariable Long id) { fotos.remover(id, Contexto.tenantId()); }

    @GetMapping("/usuarios/{id}/foto")
    public ResponseEntity<InputStreamResource> ver(@PathVariable Long id) {
        FotoService.Foto f = fotos.abrir(id, Contexto.tenantId());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(f.tipo()))
                .cacheControl(CacheControl.maxAge(1, TimeUnit.DAYS).cachePrivate())
                .body(new InputStreamResource(f.conteudo()));
    }
}
