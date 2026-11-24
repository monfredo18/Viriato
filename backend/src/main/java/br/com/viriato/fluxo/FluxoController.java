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
    private final PermissaoFluxo permissao;

    private final RequisitoService requisitos;

    public FluxoController(FluxoService service, RequisitoService requisitos, PermissaoFluxo permissao) {
        this.permissao = permissao;
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
    public record NovaEtapa(@NotBlank @Size(max = 100) String nome, Long antesDeEtapaId) {}
    /** {@code mudarPrazo}: só mexe no prazo quando verdadeiro (vazio em {@code prazoDias} = sem prazo). */
    public record DadosEtapa(@Size(max = 100) String nome, Integer prazoDias, boolean mudarPrazo) {}

    @GetMapping
    public org.springframework.http.ResponseEntity<List<FluxoService.FluxoDto>> listar(@RequestParam(required = false) Integer pagina, @RequestParam(required = false) Integer tamanho) {
        List<FluxoService.FluxoDto> visiveis = service.listar(Contexto.tenantId()).stream().filter(f -> permissao.visivel(f.id())).toList();   // cada um vê os fluxos das entregas que enxerga
        return br.com.viriato.comum.Paginacao.recortar(visiveis, pagina, tamanho, 100, 200);
    }

    @GetMapping("/{id}")
    public FluxoService.FluxoDto obter(@PathVariable Long id) {
        if (!permissao.visivel(id)) throw new br.com.viriato.comum.NaoEncontradoException("Fluxo");
        return service.obter(id, Contexto.tenantId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")   // fluxo novo nasce junto com a entrega (POST /entregas); este atalho é do administrador
    public FluxoService.FluxoDto criar(@Valid @RequestBody NovoFluxo r) {
        return service.criar(Contexto.tenantId(), r.nome(), r.etapas(), r.transicoes());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN','GESTOR') and @permissaoFluxo.pode(#id)")
    public void apagar(@PathVariable Long id) { service.apagar(id, Contexto.tenantId()); }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','GESTOR') and @permissaoFluxo.pode(#id)")
    public FluxoService.FluxoDto ativo(@PathVariable Long id, @RequestBody AtivoReq r) {
        return service.alterarAtivo(id, Contexto.tenantId(), r.ativo());
    }

    // ---- requisitos para sair de uma etapa (administrador e gestor configuram; todos consultam pelo fluxo) ----

    @PostMapping("/{fluxoId}/etapas/{etapaId}/requisitos")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','GESTOR') and @permissaoFluxo.pode(#fluxoId)")
    public RequisitoService.RequisitoDto criarRequisito(@PathVariable Long fluxoId, @PathVariable Long etapaId, @Valid @RequestBody NovoRequisito r) {
        return requisitos.criar(Contexto.tenantId(), fluxoId, etapaId, r.tipo(), r.descricao());
    }

    @PatchMapping("/{fluxoId}/etapas/{etapaId}/requisitos/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','GESTOR') and @permissaoFluxo.pode(#fluxoId)")
    public RequisitoService.RequisitoDto renomearRequisito(@PathVariable Long fluxoId, @PathVariable Long etapaId, @PathVariable Long id,
                                                           @Valid @RequestBody DescricaoReq r) {
        return requisitos.renomear(Contexto.tenantId(), fluxoId, etapaId, id, r.descricao());
    }

    @DeleteMapping("/{fluxoId}/etapas/{etapaId}/requisitos/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN','GESTOR') and @permissaoFluxo.pode(#fluxoId)")
    public void apagarRequisito(@PathVariable Long fluxoId, @PathVariable Long etapaId, @PathVariable Long id) {
        requisitos.apagar(Contexto.tenantId(), fluxoId, etapaId, id);
    }

    // ---- etapas do fluxo: criar, renomear, definir o prazo para concluir e remover ----

    @PostMapping("/{fluxoId}/etapas")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','GESTOR') and @permissaoFluxo.pode(#fluxoId)")
    public FluxoService.FluxoDto novaEtapa(@PathVariable Long fluxoId, @Valid @RequestBody NovaEtapa r) {
        return service.adicionarEtapa(Contexto.tenantId(), fluxoId, r.nome(), r.antesDeEtapaId());
    }

    @PatchMapping("/{fluxoId}/etapas/{etapaId}/dados")
    @PreAuthorize("hasAnyRole('ADMIN','GESTOR') and @permissaoFluxo.pode(#fluxoId)")
    public FluxoService.FluxoDto dadosDaEtapa(@PathVariable Long fluxoId, @PathVariable Long etapaId, @Valid @RequestBody DadosEtapa r) {
        return service.alterarEtapa(Contexto.tenantId(), fluxoId, etapaId, r.nome(), r.prazoDias(), r.mudarPrazo());
    }

    @DeleteMapping("/{fluxoId}/etapas/{etapaId}")
    @PreAuthorize("hasAnyRole('ADMIN','GESTOR') and @permissaoFluxo.pode(#fluxoId)")
    public FluxoService.FluxoDto removerEtapa(@PathVariable Long fluxoId, @PathVariable Long etapaId) {
        return service.removerEtapa(Contexto.tenantId(), fluxoId, etapaId);
    }

    /** Quem pode mover processos PARA a etapa; os demais precisam solicitar aprovação. */
    @PatchMapping("/{fluxoId}/etapas/{etapaId}")
    @PreAuthorize("hasAnyRole('ADMIN','GESTOR') and @permissaoFluxo.pode(#fluxoId)")
    public FluxoService.FluxoDto.EtapaResumo etapa(@PathVariable Long fluxoId, @PathVariable Long etapaId, @Valid @RequestBody PapelMinimoReq r) {
        return service.definirPapelMinimo(Contexto.tenantId(), fluxoId, etapaId, r.papelMinimo());
    }
}
