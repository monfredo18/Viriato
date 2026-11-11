package br.com.viriato.cadastro;

import br.com.viriato.cadastro.CadastroService.ClienteDto;
import br.com.viriato.cadastro.CadastroService.EntregaDto;
import br.com.viriato.cadastro.CadastroService.SetorDto;
import br.com.viriato.seguranca.Contexto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Consulta liberada a qualquer usuário da empresa (o formulário de processo precisa das listas).
 * Alteração (cadastrar, editar, apagar): empresas, setores e entregas, ADMIN e GESTOR.
 */
@RestController
@RequestMapping("/api")
public class CadastroController {
    private final CadastroService service;

    public CadastroController(CadastroService service) { this.service = service; }

    public record NovoCliente(@NotBlank @Size(max = 150) String nome) {}
    public record AtualizaCliente(@Size(max = 150) String nome, Boolean ativo) {}
    public record NovaEntrega(@NotBlank @Size(max = 150) String nome, @NotNull Long fluxoId) {}
    public record AtualizaEntrega(@Size(max = 150) String nome, Long fluxoId, Boolean ativo) {}
    public record NovoSetor(@NotBlank @Size(max = 100) String nome, List<Long> entregaIds) {}
    public record AtualizaSetor(@Size(max = 100) String nome, Boolean ativo, List<Long> entregaIds) {}

    // ----- empresas -----
    @GetMapping("/clientes")
    public List<ClienteDto> clientes() { return service.listarClientes(Contexto.tenantId()); }

    @PostMapping("/clientes")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','GESTOR')")
    public ClienteDto criarCliente(@Valid @RequestBody NovoCliente r) {
        return service.criarCliente(Contexto.tenantId(), r.nome());
    }

    @PatchMapping("/clientes/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','GESTOR')")
    public ClienteDto atualizarCliente(@PathVariable Long id, @Valid @RequestBody AtualizaCliente r) {
        return service.atualizarCliente(Contexto.tenantId(), id, r.nome(), r.ativo());
    }

    // ----- entregas -----
    @GetMapping("/entregas")
    public List<EntregaDto> entregas() { return service.listarEntregas(Contexto.tenantId()); }

    @PostMapping("/entregas")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','GESTOR')")
    public EntregaDto criarEntrega(@Valid @RequestBody NovaEntrega r) {
        return service.criarEntrega(Contexto.tenantId(), r.nome(), r.fluxoId());
    }

    @PatchMapping("/entregas/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','GESTOR')")
    public EntregaDto atualizarEntrega(@PathVariable Long id, @Valid @RequestBody AtualizaEntrega r) {
        return service.atualizarEntrega(Contexto.tenantId(), id, r.nome(), r.fluxoId(), r.ativo());
    }

    // ----- setores -----
    @GetMapping("/setores")
    public List<SetorDto> setores() { return service.listarSetores(Contexto.tenantId()); }

    @PostMapping("/setores")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','GESTOR')")
    public SetorDto criarSetor(@Valid @RequestBody NovoSetor r) {
        return service.criarSetor(Contexto.tenantId(), r.nome(), r.entregaIds());
    }

    @PatchMapping("/setores/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','GESTOR')")
    public SetorDto atualizarSetor(@PathVariable Long id, @Valid @RequestBody AtualizaSetor r) {
        return service.atualizarSetor(Contexto.tenantId(), id, r.nome(), r.ativo(), r.entregaIds());
    }
}
