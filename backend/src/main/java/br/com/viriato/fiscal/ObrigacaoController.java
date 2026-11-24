package br.com.viriato.fiscal;

import br.com.viriato.fiscal.MatrizService.*;
import br.com.viriato.seguranca.Contexto;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Matriz tributária: modelos de obrigação e o vínculo delas com as empresas atendidas. Só administrador e gestor. */
@RestController
@RequestMapping("/api")
@PreAuthorize("hasAnyRole('ADMIN','GESTOR')")
public class ObrigacaoController {
    public record Competencia(Integer ano, Integer mes) {}

    private final MatrizService matriz;
    private final GeradorObrigacoesService gerador;

    public ObrigacaoController(MatrizService matriz, GeradorObrigacoesService gerador) {
        this.matriz = matriz;
        this.gerador = gerador;
    }

    /** Gera os processos da competência (padrão: o mês anterior). Pode ser repetido: nada é duplicado. */
    @PostMapping("/obrigacoes/gerar")
    public GeradorObrigacoesService.Resultado gerar(@RequestBody(required = false) Competencia c) {
        java.time.YearMonth anterior = java.time.YearMonth.from(br.com.viriato.comum.Datas.hoje()).minusMonths(1);
        return gerador.gerar(Contexto.tenantId(), c == null || c.ano() == null ? anterior.getYear() : c.ano(), c == null || c.mes() == null ? anterior.getMonthValue() : c.mes());
    }

    @GetMapping("/obrigacoes/catalogo")
    public List<ItemCatalogo> catalogo() { return matriz.catalogo(); }

    @GetMapping("/obrigacoes/modelos")
    public List<ModeloDto> modelos() { return matriz.listar(Contexto.atual()); }

    @PostMapping("/obrigacoes/modelos") @ResponseStatus(HttpStatus.CREATED)
    public ModeloDto criar(@RequestBody ModeloReq r) { return matriz.criar(Contexto.atual(), r); }

    @PatchMapping("/obrigacoes/modelos/{id}")
    public ModeloDto alterar(@PathVariable Long id, @RequestBody ModeloReq r) { return matriz.alterar(Contexto.atual(), id, r); }

    @DeleteMapping("/obrigacoes/modelos/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void apagar(@PathVariable Long id) { matriz.apagar(Contexto.atual(), id); }

    @GetMapping("/clientes/{clienteId}/obrigacoes")
    public List<ObrigacaoDaEmpresaDto> daEmpresa(@PathVariable Long clienteId) { return matriz.daEmpresa(Contexto.atual(), clienteId); }

    @PatchMapping("/clientes/{clienteId}/obrigacoes/{modeloId}")
    public ObrigacaoDaEmpresaDto atualizar(@PathVariable Long clienteId, @PathVariable Long modeloId, @RequestBody AtualizaVinculo r) {
        return matriz.atualizarVinculo(Contexto.atual(), clienteId, modeloId, r);
    }
}
