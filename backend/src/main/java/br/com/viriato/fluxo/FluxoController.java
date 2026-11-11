package br.com.viriato.fluxo;

import br.com.viriato.seguranca.Contexto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/fluxos")
public class FluxoController {
    private final FluxoService service;

    public FluxoController(FluxoService service) { this.service = service; }

    public record NovoFluxo(@NotBlank @Size(max = 150) String nome,
                            List<FluxoService.EtapaReq> etapas,
                            List<FluxoService.TransicaoReq> transicoes) {}

    public record AtivoReq(boolean ativo) {}

    @GetMapping
    public List<FluxoService.FluxoDto> listar() { return service.listar(Contexto.tenantId()); }

    @GetMapping("/{id}")
    public FluxoService.FluxoDto obter(@PathVariable Long id) { return service.obter(id, Contexto.tenantId()); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','GESTOR')")
    public FluxoService.FluxoDto criar(@Valid @RequestBody NovoFluxo r) {
        return service.criar(Contexto.tenantId(), r.nome(), r.etapas(), r.transicoes());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN','GESTOR')")
    public void apagar(@PathVariable Long id) { service.apagar(id, Contexto.tenantId()); }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','GESTOR')")
    public FluxoService.FluxoDto ativo(@PathVariable Long id, @RequestBody AtivoReq r) {
        return service.alterarAtivo(id, Contexto.tenantId(), r.ativo());
    }
}
