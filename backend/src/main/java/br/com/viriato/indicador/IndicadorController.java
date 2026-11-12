package br.com.viriato.indicador;

import br.com.viriato.seguranca.Contexto;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/indicadores")
public class IndicadorController {
    private final IndicadorService service;

    public IndicadorController(IndicadorService service) { this.service = service; }

    /** {@code setorId} só vale para o administrador; os demais veem sempre o próprio setor. */
    @GetMapping
    public IndicadorService.Painel painel(@RequestParam(required = false) Integer dias, @RequestParam(required = false) Long setorId) {
        return service.gerar(Contexto.atual(), dias, setorId);
    }
}
