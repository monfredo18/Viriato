package br.com.viriato.crm;

import br.com.viriato.seguranca.Contexto;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Painel "Saúde cadastral e certificados": vencimentos próximos e pendências de cadastro. Só administrador e gestor. */
@RestController
@RequestMapping("/api/cadastros/saude")
@PreAuthorize("hasAnyRole('ADMIN','GESTOR')")
public class SaudeCadastralController {
    private final AlertaVencimentoService alertas;

    public SaudeCadastralController(AlertaVencimentoService alertas) { this.alertas = alertas; }

    @GetMapping
    public AlertaVencimentoService.Saude saude() { return alertas.saude(Contexto.tenantId()); }
}
