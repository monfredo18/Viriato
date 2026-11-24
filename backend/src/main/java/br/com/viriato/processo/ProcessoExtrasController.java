package br.com.viriato.processo;

import br.com.viriato.seguranca.Contexto;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Campos extras por entrega e dependências entre processos. */
@RestController
@RequestMapping("/api")
public class ProcessoExtrasController {
    public record NovaDependencia(Long predecessorId) {}
    public record Valores(Map<String, Object> valores) {}

    private final ProcessoService processos;
    private final DependenciaService dependencias;
    private final CampoExtraService campos;

    public ProcessoExtrasController(ProcessoService processos, DependenciaService dependencias, CampoExtraService campos) {
        this.processos = processos;
        this.dependencias = dependencias;
        this.campos = campos;
    }

    @PostMapping("/processos/{id}/dependencias") @ResponseStatus(HttpStatus.CREATED)
    public DependenciaService.DependenciaDto vincular(@PathVariable Long id, @RequestBody NovaDependencia r) { return dependencias.vincular(Contexto.atual(), id, r.predecessorId()); }

    @DeleteMapping("/processos/{id}/dependencias/{dependenciaId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void desvincular(@PathVariable Long id, @PathVariable Long dependenciaId) { dependencias.desvincular(Contexto.atual(), id, dependenciaId); }

    @PatchMapping("/processos/{id}/campos")
    public ProcessoDto preencher(@PathVariable Long id, @RequestBody Valores r) { return processos.definirCampos(Contexto.atual(), id, r.valores()); }

    @GetMapping("/entregas/{id}/campos")
    public List<CampoExtraService.CampoDto> campos(@PathVariable Long id) { return campos.daEntrega(Contexto.atual(), id); }

    @PutMapping("/entregas/{id}/campos")
    @PreAuthorize("hasAnyRole('ADMIN','GESTOR')")
    public List<CampoExtraService.CampoDto> definir(@PathVariable Long id, @RequestBody List<CampoExtraService.CampoReq> r) { return campos.definir(Contexto.atual(), id, r); }
}
