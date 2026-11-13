package br.com.viriato.automacao;

import br.com.viriato.processo.ProcessoService;
import br.com.viriato.seguranca.Contexto;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class AutomacaoController {
    private final AutomacaoService service;
    private final ProcessoService processos;

    public AutomacaoController(AutomacaoService service, ProcessoService processos) {
        this.service = service;
        this.processos = processos;
    }

    /** Só administrador e gestor: a regra pode trazer endereços e segredos de integração. */
    @GetMapping("/fluxos/{fluxoId}/automacoes")
    @PreAuthorize("hasAnyRole('ADMIN','GESTOR')")
    public List<AutomacaoService.RegraDto> listar(@PathVariable Long fluxoId) {
        return service.listar(Contexto.tenantId(), fluxoId);
    }

    @PostMapping("/fluxos/{fluxoId}/automacoes")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','GESTOR')")
    public AutomacaoService.RegraDto criar(@PathVariable Long fluxoId, @RequestBody AutomacaoService.RegraReq r) {
        return service.criar(Contexto.tenantId(), fluxoId, r);
    }

    @PatchMapping("/fluxos/{fluxoId}/automacoes/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','GESTOR')")
    public AutomacaoService.RegraDto atualizar(@PathVariable Long fluxoId, @PathVariable Long id, @RequestBody AutomacaoService.RegraReq r) {
        return service.atualizar(Contexto.tenantId(), fluxoId, id, r);
    }

    @DeleteMapping("/fluxos/{fluxoId}/automacoes/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN','GESTOR')")
    public void apagar(@PathVariable Long fluxoId, @PathVariable Long id) {
        service.apagar(Contexto.tenantId(), fluxoId, id);
    }

    /** O que as automações já fizeram neste processo; visível a quem enxerga o processo. */
    @GetMapping("/processos/{id}/automacoes")
    public List<AutomacaoService.ExecucaoDto> execucoes(@PathVariable Long id) {
        var quem = Contexto.atual();
        processos.buscar(quem, id);
        return service.execucoes(id, quem.tenantId());
    }
}
