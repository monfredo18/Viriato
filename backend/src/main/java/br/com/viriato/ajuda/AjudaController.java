package br.com.viriato.ajuda;

import br.com.viriato.ajuda.AjudaService.ConversaDetalhe;
import br.com.viriato.ajuda.AjudaService.ConversaResumo;
import br.com.viriato.ajuda.AjudaService.MensagemDto;
import br.com.viriato.seguranca.Contexto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** Chat de ajuda com os administradores. Quem pode o quê é decidido no serviço (pelo papel de quem pede). */
@RestController
@RequestMapping("/api/ajuda")
public class AjudaController {
    private final AjudaService service;

    public AjudaController(AjudaService service) { this.service = service; }

    public record NovaMensagem(@NotBlank @Size(max = AjudaService.MAX_TEXTO) String texto) {}

    @GetMapping("/nao-lidas")
    public Map<String, Long> naoLidas() { return Map.of("total", service.naoLidas(Contexto.atual())); }

    /** Pessoa: a própria conversa. */
    @GetMapping("/minha")
    public List<MensagemDto> minha() { return service.minhaConversa(Contexto.atual()); }

    @PostMapping("/minha")
    @ResponseStatus(HttpStatus.CREATED)
    public MensagemDto pedir(@Valid @RequestBody NovaMensagem req) { return service.pedirAjuda(Contexto.atual(), req.texto()); }

    /** Administrador: todas as conversas da empresa. */
    @GetMapping("/conversas")
    public List<ConversaResumo> conversas() { return service.conversasDosAdmins(Contexto.atual()); }

    @GetMapping("/conversas/{id}")
    public ConversaDetalhe abrir(@PathVariable Long id) { return service.abrir(Contexto.atual(), id); }

    @PostMapping("/conversas/{id}/mensagens")
    @ResponseStatus(HttpStatus.CREATED)
    public MensagemDto responder(@PathVariable Long id, @Valid @RequestBody NovaMensagem req) {
        return service.responder(Contexto.atual(), id, req.texto());
    }
}
