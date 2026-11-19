package br.com.viriato.cadastro;

import br.com.viriato.cadastro.CadastroService.ClienteDto;
import br.com.viriato.cadastro.CadastroService.EntregaDto;
import br.com.viriato.cadastro.CadastroService.SetorDto;
import br.com.viriato.seguranca.Contexto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Consulta liberada a qualquer usuário da empresa (o formulário de processo precisa das listas).
 * Alteração: empresas, ADMIN e GESTOR; setores, só ADMIN; entregas, ADMIN (de qualquer setor) e GESTOR (as do próprio setor).
 */
@RestController
@RequestMapping("/api")
public class CadastroController {
    private final CadastroService service;
    private final br.com.viriato.processo.ProcessoService processos;

    public CadastroController(CadastroService service, br.com.viriato.processo.ProcessoService processos) {
        this.service = service;
        this.processos = processos;
    }

    /** Número da empresa: código interno, só dígitos (até 10), único entre as empresas atendidas. */
    public record NovoCliente(@NotBlank @Pattern(regexp = "\\d{1,10}", message = "deve ter de 1 a 10 dígitos") String numero,
                              @NotBlank @Size(max = 150) String nome) {}
    public record AtualizaCliente(@Pattern(regexp = "\\d{1,10}", message = "deve ter de 1 a 10 dígitos") String numero,
                                  @Size(max = 150) String nome, Boolean ativo) {}
    /** Sem {@code fluxoId}, a entrega ganha um fluxo só dela, criado com as {@code etapas} informadas (ou as etapas padrão). */
    public record NovaEntrega(@NotBlank @Size(max = 150) String nome, Long fluxoId, List<br.com.viriato.fluxo.FluxoService.EtapaReq> etapas, Long setorId,
                              java.time.LocalDate prazoPadrao) {}
    /** {@code mudarPrazo}: só mexe no prazo padrão quando verdadeiro (vazio em {@code prazoPadrao} = sem prazo). */
    public record AtualizaEntrega(@Size(max = 150) String nome, Long fluxoId, Boolean ativo, java.time.LocalDate prazoPadrao, boolean mudarPrazo) {}
    public record NovoSetor(@NotBlank @Size(max = 100) String nome) {}
    public record AtualizaSetor(@Size(max = 100) String nome, Boolean ativo) {}

    // ----- empresas -----
    @GetMapping("/clientes")
    public List<ClienteDto> clientes() { return service.listarClientes(Contexto.tenantId()); }

    @PostMapping("/clientes")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','GESTOR')")
    public ClienteDto criarCliente(@Valid @RequestBody NovoCliente r) {
        return service.criarCliente(Contexto.tenantId(), r.numero(), r.nome());
    }

    @PatchMapping("/clientes/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','GESTOR')")
    public ClienteDto atualizarCliente(@PathVariable Long id, @Valid @RequestBody AtualizaCliente r) {
        return service.atualizarCliente(Contexto.tenantId(), id, r.numero(), r.nome(), r.ativo());
    }

    @DeleteMapping("/clientes/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN','GESTOR')")
    public void apagarCliente(@PathVariable Long id) { service.apagarCliente(Contexto.tenantId(), id); }

    // ----- entregas -----
    @GetMapping("/entregas")
    public List<EntregaDto> entregas() { return service.listarEntregas(Contexto.atual()); }

    /** Toda entrega pertence a um setor: o administrador informa o setor; o gestor cria no dele. Só o setor dono a vê e usa. */
    @PostMapping("/entregas")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','GESTOR')")
    public EntregaDto criarEntrega(@Valid @RequestBody NovaEntrega r) {
        return service.criarEntrega(Contexto.atual(), r.nome(), r.fluxoId(), r.etapas(), r.setorId(), r.prazoPadrao());
    }

    @PatchMapping("/entregas/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','GESTOR')")
    public EntregaDto atualizarEntrega(@PathVariable Long id, @Valid @RequestBody AtualizaEntrega r) {
        return service.atualizarEntrega(Contexto.atual(), id, r.nome(), r.fluxoId(), r.ativo(), r.prazoPadrao(), r.mudarPrazo());
    }

    public record ApagarEntrega(Long entregaSubstitutaId) {}

    /** Quantos processos em andamento a entrega tem (se houver, o apagar pede a entrega substituta). */
    @GetMapping("/entregas/{id}/pendencias")
    @PreAuthorize("hasAnyRole('ADMIN','GESTOR')")
    public java.util.Map<String, Long> pendenciasEntrega(@PathVariable Long id) {
        return java.util.Map.of("emAndamento", service.emAndamentoDaEntrega(Contexto.atual(), id));
    }

    /** Apaga a entrega; os processos em andamento dela passam para a entrega substituta escolhida. */
    @PostMapping("/entregas/{id}/apagar")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN','GESTOR')")
    @org.springframework.transaction.annotation.Transactional
    public void apagarComSubstituta(@PathVariable Long id, @RequestBody(required = false) ApagarEntrega r) {
        var quem = Contexto.atual();
        var entrega = service.entregaParaApagar(quem, id);
        processos.substituirEntregaEmAndamento(quem, entrega, r == null ? null : r.entregaSubstitutaId());
        service.apagarEntrega(quem, id);
    }

    @DeleteMapping("/entregas/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN','GESTOR')")
    public void apagarEntrega(@PathVariable Long id) { service.apagarEntrega(Contexto.atual(), id); }

    // ----- setores -----
    @GetMapping("/setores")
    public List<SetorDto> setores() { return service.listarSetores(Contexto.atual()); }

    @PostMapping("/setores")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public SetorDto criarSetor(@Valid @RequestBody NovoSetor r) {
        return service.criarSetor(Contexto.tenantId(), r.nome());
    }

    @PatchMapping("/setores/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public SetorDto atualizarSetor(@PathVariable Long id, @Valid @RequestBody AtualizaSetor r) {
        return service.atualizarSetor(Contexto.tenantId(), id, r.nome(), r.ativo());
    }

    @DeleteMapping("/setores/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void apagarSetor(@PathVariable Long id) { service.apagarSetor(Contexto.tenantId(), id); }
}
