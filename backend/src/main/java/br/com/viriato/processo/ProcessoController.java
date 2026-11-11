package br.com.viriato.processo;

import br.com.viriato.processo.ProcessoDto.Detalhe;
import br.com.viriato.seguranca.Contexto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/processos")
public class ProcessoController {
    private final ProcessoService service;

    public ProcessoController(ProcessoService service) { this.service = service; }

    /** Empresa, entrega, setor e responsável são obrigatórios. */
    public record NovoProcesso(@NotNull Long clienteId,
                               @NotNull Long entregaId,
                               @NotNull Long setorId,
                               @NotNull Long responsavelId,
                               @Size(max = 10000) String descricao,
                               Map<String, Object> camposExtras) {}

    public record AvancarReq(@NotNull Long etapaId, @Size(max = 2000) String comentario) {}
    public record ComentarioReq(@NotBlank @Size(max = 2000) String texto) {}
    public record AtribuirReq(Long responsavelId) {}
    public record CancelarReq(@NotBlank @Size(max = 2000) String motivo) {}

    @GetMapping
    public Page<ProcessoDto> listar(@RequestParam(required = false) StatusProcesso status,
                                    @RequestParam(required = false) Long etapaId,
                                    @RequestParam(required = false) Long responsavelId,
                                    @RequestParam(required = false) Long clienteId,
                                    @RequestParam(required = false) Long setorId,
                                    @RequestParam(required = false) Long entregaId,
                                    @RequestParam(required = false) String busca,
                                    @RequestParam(defaultValue = "false") boolean atrasados,
                                    @RequestParam(defaultValue = "false") boolean meus,
                                    @RequestParam(defaultValue = "0") int pagina,
                                    @RequestParam(defaultValue = "20") int tamanho) {
        var u = Contexto.atual();
        Long resp = meus ? u.id() : responsavelId;
        var page = PageRequest.of(Math.max(pagina, 0), Math.min(Math.max(tamanho, 1), 100),
                Sort.by(Sort.Direction.DESC, "id"));
        return service.listar(u.tenantId(), new ProcessoService.Filtro(status, etapaId, resp, clienteId, setorId, entregaId, busca, atrasados), page);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProcessoDto criar(@Valid @RequestBody NovoProcesso r) {
        return service.criar(Contexto.atual(), new ProcessoService.NovoProcesso(
                r.clienteId(), r.entregaId(), r.setorId(), r.responsavelId(), r.descricao(), r.camposExtras()));
    }

    @GetMapping("/{id}")
    public Detalhe detalhe(@PathVariable Long id) { return service.detalhe(id, Contexto.tenantId()); }

    @PostMapping("/{id}/avancar")
    public ProcessoDto avancar(@PathVariable Long id, @Valid @RequestBody AvancarReq r) {
        return service.avancar(Contexto.atual(), id, r.etapaId(), r.comentario());
    }

    @PostMapping("/{id}/comentarios")
    @ResponseStatus(HttpStatus.CREATED)
    public void comentar(@PathVariable Long id, @Valid @RequestBody ComentarioReq r) {
        service.comentar(Contexto.atual(), id, r.texto());
    }

    @PutMapping("/{id}/responsavel")
    public ProcessoDto atribuir(@PathVariable Long id, @RequestBody AtribuirReq r) {
        return service.atribuir(Contexto.atual(), id, r.responsavelId());
    }

    @PostMapping("/{id}/cancelar")
    public ProcessoDto cancelar(@PathVariable Long id, @Valid @RequestBody CancelarReq r) {
        return service.cancelar(Contexto.atual(), id, r.motivo());
    }
}
