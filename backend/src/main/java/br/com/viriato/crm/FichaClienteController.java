package br.com.viriato.crm;

import br.com.viriato.cadastro.Cliente;
import br.com.viriato.cadastro.ClienteRepository;
import br.com.viriato.comum.NaoEncontradoException;
import br.com.viriato.crm.CertificadoService.AtualizaCertificado;
import br.com.viriato.crm.CertificadoService.CertificadoDto;
import br.com.viriato.crm.CertificadoService.NovoA3;
import br.com.viriato.crm.ChecklistService.ItemDto;
import br.com.viriato.crm.Enums.RegimeTributario;
import br.com.viriato.crm.Enums.StatusContratual;
import br.com.viriato.crm.ProcuracaoService.ProcuracaoDto;
import br.com.viriato.crm.ProcuracaoService.ProcuracaoReq;
import br.com.viriato.crm.SocioContatoService.ContatoDto;
import br.com.viriato.crm.SocioContatoService.ContatoReq;
import br.com.viriato.crm.SocioContatoService.SocioDto;
import br.com.viriato.crm.SocioContatoService.SocioReq;
import br.com.viriato.seguranca.Contexto;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * Ficha completa da empresa atendida: dados fiscais, sócios, contatos, certificados, procurações e checklists.
 * Só administrador e gestor (a ficha tem dados pessoais e sigilosos); outra empresa responde "não encontrado".
 */
@RestController
@RequestMapping("/api/clientes/{clienteId}")
@PreAuthorize("hasAnyRole('ADMIN','GESTOR')")
public class FichaClienteController {
    public record FiscalDto(String cnpj, String cnpjFormatado, String razaoSocial, String nomeFantasia, String inscricaoEstadual, String inscricaoMunicipal,
                            String cnaePrincipal, String cnaePrincipalDescricao, List<DadosFiscais.Cnae> cnaesSecundarios, RegimeTributario regimeTributario,
                            String uf, String codigoMunicipioIbge, LocalDate dataAberturaEmpresa, StatusContratual statusContratual) {}
    public record FichaDto(Long id, String numero, String nome, boolean ativo, FiscalDto fiscal, List<SocioDto> socios, List<ContatoDto> contatos,
                           List<CertificadoDto> certificados, List<ProcuracaoDto> procuracoes, List<ItemDto> checklist) {}
    public record MarcaItem(boolean concluido) {}

    private final ClienteRepository clientes;
    private final FichaFiscalService fiscal;
    private final SocioContatoService sc;
    private final CertificadoService certs;
    private final ProcuracaoService procs;
    private final ChecklistService checklist;

    public FichaClienteController(ClienteRepository clientes, FichaFiscalService fiscal, SocioContatoService sc, CertificadoService certs,
                                  ProcuracaoService procs, ChecklistService checklist) {
        this.clientes = clientes;
        this.fiscal = fiscal;
        this.sc = sc;
        this.certs = certs;
        this.procs = procs;
        this.checklist = checklist;
    }

    @GetMapping("/ficha")
    @Transactional(readOnly = true)
    public FichaDto ficha(@PathVariable Long clienteId) {
        Long t = Contexto.tenantId();
        Cliente c = clientes.findByIdAndTenantId(clienteId, t).orElseThrow(() -> new NaoEncontradoException("Empresa"));
        FiscalDto f = new FiscalDto(c.getCnpj(), Documentos.formatarCnpj(c.getCnpj()), c.getRazaoSocial(), c.getNomeFantasia(), c.getInscricaoEstadual(),
                c.getInscricaoMunicipal(), c.getCnaePrincipal(), c.getCnaePrincipalDescricao(), fiscal.cnaesSecundarios(c), c.getRegimeTributario(), c.getUf(),
                c.getCodigoMunicipioIbge(), c.getDataAberturaEmpresa(), c.getStatusContratual());
        return new FichaDto(c.getId(), c.getNumero(), c.getNome(), c.isAtivo(), f, sc.socios(t, clienteId), sc.contatos(t, clienteId),
                certs.listar(t, clienteId), procs.listar(t, clienteId), checklist.listar(t, clienteId));
    }

    // ---------- sócios ----------
    @PostMapping("/socios") @ResponseStatus(HttpStatus.CREATED)
    public SocioDto criarSocio(@PathVariable Long clienteId, @RequestBody SocioReq r) { return sc.criarSocio(Contexto.tenantId(), clienteId, r); }
    @PatchMapping("/socios/{id}")
    public SocioDto alterarSocio(@PathVariable Long clienteId, @PathVariable Long id, @RequestBody SocioReq r) { return sc.alterarSocio(Contexto.tenantId(), clienteId, id, r); }
    @DeleteMapping("/socios/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void apagarSocio(@PathVariable Long clienteId, @PathVariable Long id) { sc.apagarSocio(Contexto.tenantId(), clienteId, id); }

    // ---------- contatos ----------
    @PostMapping("/contatos") @ResponseStatus(HttpStatus.CREATED)
    public ContatoDto criarContato(@PathVariable Long clienteId, @RequestBody ContatoReq r) { return sc.criarContato(Contexto.tenantId(), clienteId, r); }
    @PatchMapping("/contatos/{id}")
    public ContatoDto alterarContato(@PathVariable Long clienteId, @PathVariable Long id, @RequestBody ContatoReq r) { return sc.alterarContato(Contexto.tenantId(), clienteId, id, r); }
    @DeleteMapping("/contatos/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void apagarContato(@PathVariable Long clienteId, @PathVariable Long id) { sc.apagarContato(Contexto.tenantId(), clienteId, id); }

    // ---------- certificados (o arquivo e a senha nunca saem) ----------
    @PostMapping(value = "/certificados/a1", consumes = MediaType.MULTIPART_FORM_DATA_VALUE) @ResponseStatus(HttpStatus.CREATED)
    public CertificadoDto enviarA1(@PathVariable Long clienteId, @RequestParam("arquivo") MultipartFile arquivo, @RequestParam("senha") String senha,
                                   @RequestParam(value = "observacao", required = false) String observacao) throws IOException {
        return certs.enviarA1(Contexto.atual(), clienteId, arquivo, senha, observacao);
    }
    @PostMapping("/certificados/a3") @ResponseStatus(HttpStatus.CREATED)
    public CertificadoDto registrarA3(@PathVariable Long clienteId, @RequestBody NovoA3 r) { return certs.registrarA3(Contexto.atual(), clienteId, r); }
    @PatchMapping("/certificados/{id}")
    public CertificadoDto alterarCertificado(@PathVariable Long clienteId, @PathVariable Long id, @RequestBody AtualizaCertificado r) { return certs.alterar(Contexto.atual(), clienteId, id, r); }
    @DeleteMapping("/certificados/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void apagarCertificado(@PathVariable Long clienteId, @PathVariable Long id) { certs.apagar(Contexto.atual(), clienteId, id); }

    // ---------- procurações ----------
    @PostMapping(value = "/procuracoes", consumes = MediaType.MULTIPART_FORM_DATA_VALUE) @ResponseStatus(HttpStatus.CREATED)
    public ProcuracaoDto criarProcuracao(@PathVariable Long clienteId, @RequestParam("tipo") Enums.TipoProcuracao tipo,
                                         @RequestParam("dataOutorga") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataOutorga,
                                         @RequestParam(value = "dataVencimento", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataVencimento,
                                         @RequestParam(value = "poderes", required = false) String poderes,
                                         @RequestParam(value = "arquivo", required = false) MultipartFile arquivo) throws IOException {
        return procs.criar(Contexto.tenantId(), clienteId, new ProcuracaoReq(tipo, dataOutorga, dataVencimento, false, poderes, null), arquivo);
    }
    @PatchMapping("/procuracoes/{id}")
    public ProcuracaoDto alterarProcuracao(@PathVariable Long clienteId, @PathVariable Long id, @RequestBody ProcuracaoReq r) { return procs.alterar(Contexto.tenantId(), clienteId, id, r); }
    @PostMapping(value = "/procuracoes/{id}/comprovante", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ProcuracaoDto trocarComprovante(@PathVariable Long clienteId, @PathVariable Long id, @RequestParam("arquivo") MultipartFile arquivo) throws IOException {
        return procs.trocarComprovante(Contexto.tenantId(), clienteId, id, arquivo);
    }
    @GetMapping("/procuracoes/{id}/comprovante")
    public ResponseEntity<org.springframework.core.io.InputStreamResource> comprovante(@PathVariable Long clienteId, @PathVariable Long id) {
        var c = procs.baixar(Contexto.tenantId(), clienteId, id);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(c.nome()).build().toString())
                .body(new org.springframework.core.io.InputStreamResource(c.conteudo()));
    }
    @DeleteMapping("/procuracoes/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void apagarProcuracao(@PathVariable Long clienteId, @PathVariable Long id) { procs.apagar(Contexto.tenantId(), clienteId, id); }

    // ---------- checklist ----------
    @GetMapping("/checklist")
    public List<ItemDto> checklist(@PathVariable Long clienteId) { return checklist.listar(Contexto.tenantId(), clienteId); }
    @PatchMapping("/checklist/{itemId}")
    public ItemDto marcar(@PathVariable Long clienteId, @PathVariable Long itemId, @RequestBody MarcaItem r) { return checklist.marcar(Contexto.atual(), clienteId, itemId, r.concluido()); }
}
