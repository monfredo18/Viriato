package br.com.viriato.fiscal;

import br.com.viriato.fiscal.CalendarioFiscalService.FeriadoDto;
import br.com.viriato.fiscal.CalendarioFiscalService.FeriadoReq;
import br.com.viriato.seguranca.Contexto;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Feriados da empresa: todos consultam (o calendário marca os dias sem expediente); só administrador e gestor alteram. */
@RestController
@RequestMapping("/api/feriados")
public class FeriadoController {
    private final CalendarioFiscalService service;

    public FeriadoController(CalendarioFiscalService service) { this.service = service; }

    @GetMapping
    public List<FeriadoDto> listar() { return service.listar(Contexto.tenantId()); }

    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','GESTOR')")
    public FeriadoDto criar(@RequestBody FeriadoReq r) { return service.criar(Contexto.tenantId(), r); }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','GESTOR')")
    public FeriadoDto alterar(@PathVariable Long id, @RequestBody FeriadoReq r) { return service.alterar(Contexto.tenantId(), id, r); }

    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN','GESTOR')")
    public void apagar(@PathVariable Long id) { service.apagar(Contexto.tenantId(), id); }
}
