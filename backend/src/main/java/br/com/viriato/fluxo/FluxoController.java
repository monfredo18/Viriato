package br.com.viriato.fluxo;

import br.com.viriato.seguranca.Contexto;
import br.com.viriato.usuario.Papel;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/fluxos")
public class FluxoController {
    private final FluxoService service;

    private final RequisitoService requisitos;

    public FluxoController(FluxoService service, RequisitoService requisitos) {
        this.service = service;
        this.requisitos = requisitos;
    }

    public record NovoRequisito(@NotNull TipoRequisito tipo, @NotBlank @Size(max = 200) String descricao) {}
    public record DescricaoReq(@NotBlank @Size(max = 200) String descricao) {}
    public record PapelMinimoReq(@NotNull Papel papelMinimo) {}

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

    // ---- requisitos para sair de uma etapa (administrador e gestor configuram; todos consultam pelo fluxo) ----

    @PostMapping("/{fluxoId}/etapas/{etapaId}/requisitos")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','GESTOR')")
    public RequisitoService.RequisitoDto criarRequisito(@PathVariable Long fluxoId, @PathVariable Long etapaId, @Valid @RequestBody NovoRequisito r) {
        return requisitos.criar(Contexto.tenantId(), fluxoId, etapaId, r.tipo(), r.descricao());
    }

    @PatchMapping("/{fluxoId}/etapas/{etapaId}/requisitos/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','GESTOR')")
    public RequisitoService.RequisitoDto renomearRequisito(@PathVariable Long fluxoId, @PathVariable Long etapaId, @PathVariable Long id,
                                                           @Valid @RequestBody DescricaoReq r) {
        return requisitos.renomear(Contexto.tenantId(), fluxoId, etapaId, id, r.descricao());
    }

    @DeleteMapping("/{fluxoId}/etapas/{etapaId}/requisitos/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN','GESTOR')")
    public void apagarRequisito(@PathVariable Long fluxoId, @PathVariable Long etapaId, @PathVariable Long id) {
        requisitos.apagar(Contexto.tenantId(), fluxoId, etapaId, id);
    }

    /** Quem pode mover processos PARA a etapa; os demais precisam solicitar aprovação. */
    @PatchMapping("/{fluxoId}/etapas/{etapaId}")
    @PreAuthorize("hasAnyRole('ADMIN','GESTOR')")
    public FluxoService.FluxoDto.EtapaResumo etapa(@PathVariable Long fluxoId, @PathVariable Long etapaId, @Valid @RequestBody PapelMinimoReq r) {
        return service.definirPapelMinimo(Contexto.tenantId(), fluxoId, etapaId, r.papelMinimo());
    }
}
