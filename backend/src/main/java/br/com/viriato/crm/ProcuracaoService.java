package br.com.viriato.crm;

import br.com.viriato.cadastro.Cliente;
import br.com.viriato.cadastro.ClienteRepository;
import br.com.viriato.comum.Datas;
import br.com.viriato.comum.NaoEncontradoException;
import br.com.viriato.comum.NegocioException;
import br.com.viriato.crm.Enums.TipoProcuracao;
import br.com.viriato.documento.ArmazenamentoArquivos;
import br.com.viriato.documento.DocumentoService;
import br.com.viriato.documento.ValidadorDeArquivo;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/** Procurações eletrônicas (e-CAC, SEFAZ, Prefeitura, FGTS) com data de vencimento e o comprovante em PDF. */
@Service
public class ProcuracaoService {
    public enum Situacao { VIGENTE, VENCENDO, VENCIDA, SEM_VENCIMENTO }

    public record ProcuracaoDto(Long id, TipoProcuracao tipo, LocalDate dataOutorga, LocalDate dataVencimento, String poderes,
                                boolean temComprovante, String comprovanteNome, Situacao situacao, Long diasParaVencer, boolean ativa) {}

    /** Na alteração, campo nulo não muda. {@code semVencimento=true} apaga a data de vencimento. */
    public record ProcuracaoReq(TipoProcuracao tipo, LocalDate dataOutorga, LocalDate dataVencimento, boolean semVencimento, String poderes, Boolean ativa) {}

    public record Comprovante(String nome, InputStream conteudo) {}

    private final ClienteProcuracaoRepository procuracoes;
    private final ClienteRepository clientes;
    private final AlertaVencimentoRepository alertas;
    private final ArmazenamentoArquivos storage;
    private final DocumentoService documentos;

    public ProcuracaoService(ClienteProcuracaoRepository procuracoes, ClienteRepository clientes, AlertaVencimentoRepository alertas,
                             ArmazenamentoArquivos storage, DocumentoService documentos) {
        this.procuracoes = procuracoes;
        this.clientes = clientes;
        this.alertas = alertas;
        this.storage = storage;
        this.documentos = documentos;
    }

    @Transactional(readOnly = true)
    public List<ProcuracaoDto> listar(Long t, Long clienteId) {
        return procuracoes.findByClienteIdAndTenantIdOrderByIdDesc(clienteId, t).stream().map(ProcuracaoService::dto).toList();
    }

    @Transactional
    public ProcuracaoDto criar(Long t, Long clienteId, ProcuracaoReq r, MultipartFile comprovante) throws IOException {
        clientes.findByIdAndTenantId(clienteId, t).orElseThrow(() -> new NaoEncontradoException("Empresa"));
        if (r.tipo() == null) throw new NegocioException("Escolha o tipo da procuração");
        if (r.dataOutorga() == null) throw new NegocioException("Informe a data de outorga");
        ClienteProcuracao p = new ClienteProcuracao();
        p.setTenantId(t);
        p.setClienteId(clienteId);
        aplicar(p, r);
        p = procuracoes.save(p);
        if (comprovante != null && !comprovante.isEmpty()) guardar(p, comprovante);
        return dto(procuracoes.save(p));
    }

    @Transactional
    public ProcuracaoDto alterar(Long t, Long clienteId, Long id, ProcuracaoReq r) {
        ClienteProcuracao p = procuracoes.findByIdAndClienteIdAndTenantId(id, clienteId, t).orElseThrow(() -> new NaoEncontradoException("Procuração"));
        LocalDate antes = p.getDataVencimento();
        aplicar(p, r);
        if (p.getDataVencimento() == null ? antes != null : !p.getDataVencimento().equals(antes)) alertas.deleteByTipoAndRefId("PROCURACAO", p.getId());   // renovou: os avisos voltam a valer
        return dto(procuracoes.save(p));
    }

    @Transactional
    public ProcuracaoDto trocarComprovante(Long t, Long clienteId, Long id, MultipartFile arquivo) throws IOException {
        ClienteProcuracao p = procuracoes.findByIdAndClienteIdAndTenantId(id, clienteId, t).orElseThrow(() -> new NaoEncontradoException("Procuração"));
        if (arquivo == null || arquivo.isEmpty()) throw new NegocioException("Envie o comprovante em PDF");
        String antigo = p.getComprovanteChave();
        guardar(p, arquivo);
        p = procuracoes.save(p);
        if (antigo != null) storage.remover(antigo);
        return dto(p);
    }

    @Transactional(readOnly = true)
    public Comprovante baixar(Long t, Long clienteId, Long id) {
        ClienteProcuracao p = procuracoes.findByIdAndClienteIdAndTenantId(id, clienteId, t).orElseThrow(() -> new NaoEncontradoException("Procuração"));
        if (p.getComprovanteChave() == null) throw new NaoEncontradoException("Comprovante");
        return new Comprovante(p.getComprovanteNome(), storage.abrir(p.getComprovanteChave()));
    }

    @Transactional
    public void apagar(Long t, Long clienteId, Long id) {
        ClienteProcuracao p = procuracoes.findByIdAndClienteIdAndTenantId(id, clienteId, t).orElseThrow(() -> new NaoEncontradoException("Procuração"));
        procuracoes.delete(p);
        alertas.deleteByTipoAndRefId("PROCURACAO", p.getId());
        if (p.getComprovanteChave() != null) storage.remover(p.getComprovanteChave());
    }

    public void removerArquivosDaEmpresa(Long t, Long clienteId) {
        for (ClienteProcuracao p : procuracoes.findByClienteIdAndTenantIdOrderByIdDesc(clienteId, t)) {
            if (p.getComprovanteChave() != null) storage.remover(p.getComprovanteChave());
        }
    }

    private void aplicar(ClienteProcuracao p, ProcuracaoReq r) {
        if (r.tipo() != null) p.setTipo(r.tipo());
        if (r.dataOutorga() != null) p.setDataOutorga(r.dataOutorga());
        if (r.semVencimento()) p.setDataVencimento(null);
        else if (r.dataVencimento() != null) p.setDataVencimento(r.dataVencimento());
        if (p.getDataVencimento() != null && p.getDataVencimento().isBefore(p.getDataOutorga())) {
            throw new NegocioException("O vencimento não pode ser anterior à data de outorga");
        }
        if (r.poderes() != null) {
            if (r.poderes().length() > 1000) throw new NegocioException("Os poderes outorgados devem ter até 1000 caracteres");
            p.setPoderes(r.poderes().isBlank() ? null : r.poderes().trim());
        }
        if (r.ativa() != null) p.setAtivo(r.ativa());
    }

    private void guardar(ClienteProcuracao p, MultipartFile arquivo) throws IOException {
        String nome = arquivo.getOriginalFilename() == null ? "comprovante.pdf" : arquivo.getOriginalFilename();
        if (!ValidadorDeArquivo.extensao(nome).equals("pdf")) throw new NegocioException("O comprovante deve ser um PDF");
        documentos.validarConteudo(nome, arquivo);
        String limpo = nome.replaceAll("[^\\p{L}\\p{N}._ -]", "_");
        String chave = "tenant-%d/cliente-%d/procuracoes/%s-%s".formatted(p.getTenantId(), p.getClienteId(), UUID.randomUUID(), limpo);
        try (InputStream in = arquivo.getInputStream()) { storage.salvar(chave, in, arquivo.getSize(), "application/pdf"); }
        p.setComprovanteChave(chave);
        p.setComprovanteNome(limpo);
    }

    static ProcuracaoDto dto(ClienteProcuracao p) {
        Long dias = p.getDataVencimento() == null ? null : java.time.temporal.ChronoUnit.DAYS.between(Datas.hoje(), p.getDataVencimento());
        Situacao s = dias == null ? Situacao.SEM_VENCIMENTO : dias < 0 ? Situacao.VENCIDA : dias <= 30 ? Situacao.VENCENDO : Situacao.VIGENTE;
        return new ProcuracaoDto(p.getId(), p.getTipo(), p.getDataOutorga(), p.getDataVencimento(), p.getPoderes(), p.getComprovanteChave() != null,
                p.getComprovanteNome(), s, dias, p.isAtivo());
    }
}
