package br.com.viriato.ged;

import br.com.viriato.seguranca.Contexto;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** GED e notas fiscais em XML da empresa atendida. Só administrador e gestor. */
@RestController
@RequestMapping("/api")
@PreAuthorize("hasAnyRole('ADMIN','GESTOR')")
public class GedController {
    private final GedService ged;
    private final ImportacaoXmlService xml;

    public GedController(GedService ged, ImportacaoXmlService xml) {
        this.ged = ged;
        this.xml = xml;
    }

    @GetMapping("/clientes/{clienteId}/ged/pastas")
    public List<GedService.PastaDto> pastas(@PathVariable Long clienteId) { return ged.pastas(Contexto.atual(), clienteId); }

    @GetMapping("/clientes/{clienteId}/ged/documentos")
    public List<GedService.GedDocumentoDto> documentos(@PathVariable Long clienteId, @RequestParam(required = false) Integer ano, @RequestParam(required = false) Integer mes,
                                                       @RequestParam(required = false) Long setorId, @RequestParam(required = false) String categoria,
                                                       @RequestParam(required = false) String busca, @RequestParam(defaultValue = "false") boolean arquivados) {
        return ged.listar(Contexto.atual(), clienteId, ano, mes, setorId, categoria, busca, arquivados);
    }

    @PostMapping(value = "/clientes/{clienteId}/ged/documentos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE) @ResponseStatus(HttpStatus.CREATED)
    public GedService.GedDocumentoDto enviar(@PathVariable Long clienteId, @RequestParam("arquivo") MultipartFile arquivo, @RequestParam(required = false) String categoria,
                                             @RequestParam(required = false) Integer ano, @RequestParam(required = false) Integer mes, @RequestParam(required = false) Long setorId,
                                             @RequestParam(required = false) Boolean guardaLegal) throws IOException {
        return ged.enviar(Contexto.atual(), clienteId, arquivo, categoria, ano, mes, setorId, guardaLegal);
    }

    /** Importa XMLs fiscais (vários arquivos ou .zip). Sem {@code clienteId}, a empresa é descoberta pelo CNPJ da nota. */
    @PostMapping(value = "/xml/importar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportacaoXmlService.Resultado importar(@RequestParam("arquivos") List<MultipartFile> arquivos, @RequestParam(required = false) Long clienteId,
                                                   @RequestParam(required = false) Long setorId) throws IOException {
        return xml.importar(Contexto.atual(), arquivos, clienteId, setorId);
    }

    @GetMapping("/clientes/{clienteId}/xml")
    public List<ImportacaoXmlService.NotaDto> notas(@PathVariable Long clienteId, @RequestParam(required = false) Integer ano, @RequestParam(required = false) Integer mes,
                                                    @RequestParam(required = false) String tipo) {
        return xml.listar(Contexto.tenantId(), clienteId, ano, mes, tipo);
    }
}
