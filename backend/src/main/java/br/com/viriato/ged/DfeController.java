package br.com.viriato.ged;

import br.com.viriato.seguranca.Contexto;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Consulta à SEFAZ (Distribuição DF-e) da empresa atendida. Só administrador e gestor. */
@RestController
@RequestMapping("/api/clientes/{clienteId}/sefaz")
@PreAuthorize("hasAnyRole('ADMIN','GESTOR')")
public class DfeController {
    private final DfeService service;

    public DfeController(DfeService service) { this.service = service; }

    @GetMapping
    public DfeService.Estado estado(@PathVariable Long clienteId) { return service.estado(Contexto.tenantId(), clienteId); }

    @PostMapping("/consultar")
    public DfeService.Resultado consultar(@PathVariable Long clienteId) { return service.consultar(Contexto.tenantId(), Contexto.atual().id(), clienteId); }
}
