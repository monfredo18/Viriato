package br.com.viriato.email;

import br.com.viriato.notificacao.EmailService;
import br.com.viriato.seguranca.Contexto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Servidor de e-mail da empresa: só o administrador consulta e altera. */
@RestController
@RequestMapping("/api/configuracao/email")
@PreAuthorize("hasRole('ADMIN')")
public class ConfigEmailController {
    private final ConfigEmailService service;
    private final EmailService email;

    public ConfigEmailController(ConfigEmailService service, EmailService email) {
        this.service = service;
        this.email = email;
    }

    public record TesteReq(@NotBlank @Email String para) {}
    public record ResultadoTeste(boolean ok, String mensagem) {}

    @GetMapping
    public ConfigEmailService.ConfigDto obter() { return service.obter(Contexto.tenantId()); }

    @PutMapping
    public ConfigEmailService.ConfigDto salvar(@RequestBody ConfigEmailService.SalvarReq r) { return service.salvar(Contexto.tenantId(), r); }

    /** Volta a usar o servidor padrão do sistema. */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remover() { service.remover(Contexto.tenantId()); }

    /** Envia uma mensagem de teste e devolve o resultado (ou o motivo exato da falha). */
    @PostMapping("/teste")
    public ResultadoTeste testar(@Valid @RequestBody TesteReq r) {
        Long tid = Contexto.tenantId();
        var cfg = service.obter(tid);
        String servidor = email.servidorEmUso(tid);
        String erro = email.enviarAgora(tid, r.para(), "Teste de e-mail do Viriato",
                "Se você recebeu esta mensagem, o envio de e-mails do Viriato está funcionando.\n\nServidor usado: " + servidor, null, null);
        if (erro != null) return new ResultadoTeste(false, erro);
        if (!cfg.configurado() && cfg.padraoSoCaptura()) {
            return new ResultadoTeste(true, "Enviado ao servidor padrão (" + servidor + "), que só CAPTURA e-mails para desenvolvimento e não entrega em caixas de entrada reais. "
                    + "Configure o seu servidor de e-mail acima para receber de verdade.");
        }
        return new ResultadoTeste(true, "E-mail de teste enviado para " + r.para() + " pelo servidor " + servidor + ". Confira a caixa de entrada (e o spam).");
    }
}
