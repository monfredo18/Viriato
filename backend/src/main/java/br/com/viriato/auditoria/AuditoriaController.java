package br.com.viriato.auditoria;

import br.com.viriato.seguranca.Contexto;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Só o administrador consulta a auditoria da própria empresa (os 300 registros mais recentes). */
@RestController
@RequestMapping("/api/auditoria")
public class AuditoriaController {
    private final AuditoriaService service;

    public AuditoriaController(AuditoriaService service) { this.service = service; }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public List<AuditoriaService.Registro> listar() { return service.recentes(Contexto.tenantId()); }
}
