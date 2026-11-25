package br.com.viriato.agente;

import br.com.viriato.agente.AgenteService.AgenteDto;
import br.com.viriato.agente.AgenteService.Contato;
import br.com.viriato.agente.AgenteService.ContatoResposta;
import br.com.viriato.agente.AgenteService.Pareamento;
import br.com.viriato.agente.AgenteService.Resultado;
import br.com.viriato.comum.NaoEncontradoException;
import br.com.viriato.crm.CertificadoService;
import br.com.viriato.crm.CertificadoService.CertificadoDto;
import br.com.viriato.crm.CertificadoService.Vinculo;
import br.com.viriato.seguranca.Contexto;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

/**
 * Três grupos de rotas: o pareamento (público, com o código gerado na tela), a gestão pelo usuário (códigos, lista de agentes,
 * certificados) e as chamadas do próprio agente (token dele).
 */
@RestController
public class AgenteController {
    public record Parear(String codigo, String nome, String versao) {}
    public record Pareado(String token) {}

    private final AgenteService agentes;
    private final CertificadoService certificados;

    public AgenteController(AgenteService agentes, CertificadoService certificados) {
        this.agentes = agentes;
        this.certificados = certificados;
    }

    /** Fica em /api/auth/ para valer o limite de tentativas por endereço (o código tem 8 caracteres e vida curta). */
    @PostMapping("/api/auth/agente/parear")
    public Pareado parear(@RequestBody Parear r) { return new Pareado(agentes.parear(r.codigo(), r.nome(), r.versao())); }

    @PostMapping("/api/agentes/codigo") @ResponseStatus(HttpStatus.CREATED)
    public Pareamento codigo() { return agentes.gerarCodigo(Contexto.atual()); }

    @GetMapping("/api/agentes")
    public List<AgenteDto> listar() { return agentes.listar(Contexto.atual()); }

    @DeleteMapping("/api/agentes/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revogar(@PathVariable Long id) { agentes.revogar(Contexto.atual(), id); }

    @GetMapping("/api/certificados") @PreAuthorize("hasAnyRole('ADMIN','GESTOR')")
    public List<CertificadoDto> certificados() { return certificados.listarTodos(Contexto.tenantId()); }

    @PatchMapping("/api/certificados/{id}") @PreAuthorize("hasAnyRole('ADMIN','GESTOR')")
    public CertificadoDto vincular(@PathVariable Long id, @RequestBody Vinculo r) { return certificados.vincular(Contexto.atual(), id, r.clienteId()); }

    // ---------- chamadas do agente ----------

    @PostMapping("/api/agente/contato")
    public ContatoResposta contato(@RequestBody(required = false) Contato c) { return agentes.contato(atual(), c); }

    @PostMapping("/api/agente/tarefas/{id}/resultado") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resultado(@PathVariable Long id, @RequestBody Resultado r) { agentes.resultado(atual(), id, r); }

    private static Agente atual() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || !(a.getPrincipal() instanceof AgenteLogado l)) throw new IllegalStateException("Sem agente autenticado");
        Agente ag = new Agente();
        ag.setId(l.id());
        return ag;
    }
}
