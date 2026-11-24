package br.com.viriato.ged;

import br.com.viriato.cadastro.Cliente;
import br.com.viriato.cadastro.ClienteRepository;
import br.com.viriato.comum.NaoEncontradoException;
import br.com.viriato.comum.NegocioException;
import br.com.viriato.documento.ArmazenamentoArquivos;
import br.com.viriato.documento.Documento;
import br.com.viriato.documento.DocumentoRepository;
import br.com.viriato.documento.DocumentoService;
import br.com.viriato.seguranca.UsuarioLogado;
import br.com.viriato.usuario.Papel;
import br.com.viriato.usuario.UsuarioRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/**
 * Importação em lote de XMLs fiscais (vários arquivos ou .zip). Cada nota é conferida (conteúdo e antivírus), lida,
 * ligada à empresa atendida pelo CNPJ, guardada na árvore arquivística (em guarda legal) e gravada com os valores
 * extraídos. A chave de acesso é única por empresa do sistema: nota repetida é contada, não duplicada. Cada arquivo tem
 * a sua transação: uma nota ruim não derruba o lote.
 */
@Service
public class ImportacaoXmlService {
    static final int MAX_ENTRADAS = 1000;
    static final long MAX_XML = 5L * 1024 * 1024;
    static final long MAX_TOTAL = 100L * 1024 * 1024;

    public record Rejeitado(String arquivo, String motivo) {}
    public record NotaDto(Long id, String chaveAcesso, String modelo, String numeroNota, String serie, java.time.LocalDate dataEmissao, Integer competenciaAno, Integer competenciaMes,
                          String cnpjEmitente, String nomeEmitente, String cnpjDestinatario, String nomeDestinatario, String tipoOperacao, BigDecimal valorTotal, BigDecimal valorIcms,
                          BigDecimal valorIpi, BigDecimal valorPis, BigDecimal valorCofins, BigDecimal valorIss, List<String> cfops, List<String> ncms, String origem, Long documentoId) {}
    public record Resultado(int lidos, int importados, int duplicados, List<Rejeitado> rejeitados, List<NotaDto> notas) {}

    private final NotaFiscalXmlRepository notas;
    private final ClienteRepository clientes;
    private final DocumentoRepository documentos;
    private final DocumentoService documentoService;
    private final GedService ged;
    private final ArvoreArquivistica arvore;
    private final ArmazenamentoArquivos storage;
    private final UsuarioRepository usuarios;
    private final ObjectMapper json;
    private final PlatformTransactionManager tm;

    public ImportacaoXmlService(NotaFiscalXmlRepository notas, ClienteRepository clientes, DocumentoRepository documentos, DocumentoService documentoService, GedService ged,
                                ArvoreArquivistica arvore, ArmazenamentoArquivos storage, UsuarioRepository usuarios, ObjectMapper json, PlatformTransactionManager tm) {
        this.notas = notas;
        this.clientes = clientes;
        this.documentos = documentos;
        this.documentoService = documentoService;
        this.ged = ged;
        this.arvore = arvore;
        this.storage = storage;
        this.usuarios = usuarios;
        this.json = json;
        this.tm = tm;
    }

    /** Importação feita por uma pessoa (tela ou API). {@code clienteId} vazio = descobre a empresa pelo CNPJ da nota. */
    public Resultado importar(UsuarioLogado quem, List<MultipartFile> arquivos, Long clienteId, Long setorId) throws IOException {
        Cliente alvo = clienteId == null ? null : clientes.findByIdAndTenantId(clienteId, quem.tenantId()).orElseThrow(() -> new NaoEncontradoException("Empresa"));
        Long setor = setorId;
        if (quem.papel() == Papel.GESTOR) {
            Long meu = usuarios.setorDe(quem);
            if (setor != null && !setor.equals(meu)) throw new NaoEncontradoException("Setor");
            if (setor == null) setor = meu;
        }
        if (arquivos == null || arquivos.isEmpty()) throw new NegocioException("Envie ao menos um arquivo .xml ou .zip");
        List<Rejeitado> rej = new ArrayList<>();
        List<NotaDto> ok = new ArrayList<>();
        int[] contagem = new int[3];   // lidos, importados, duplicados
        final Long setorFinal = setor;
        for (MultipartFile f : arquivos) {
            String nome = GedService.limpar(f.getOriginalFilename());
            String ext = br.com.viriato.documento.ValidadorDeArquivo.extensao(nome);
            try {
                if (f.getSize() > MAX_TOTAL) throw new NegocioException("Arquivo maior que 100 MB");
                if (ext.equals("zip")) {
                    documentoService.validarConteudo(nome, f);   // o próprio .zip: conteúdo e antivírus
                    lerZip(f.getBytes(), nome, (n, b) -> uma(quem.tenantId(), quem.id(), alvo, setorFinal, n, b, "UPLOAD", contagem, ok, rej));
                } else if (ext.equals("xml")) {
                    uma(quem.tenantId(), quem.id(), alvo, setorFinal, nome, f.getBytes(), "UPLOAD", contagem, ok, rej);
                } else {
                    throw new NegocioException("Só arquivos .xml ou .zip com XMLs");
                }
            } catch (NegocioException e) {
                contagem[0]++;
                rej.add(new Rejeitado(nome, e.getMessage()));
            }
        }
        return new Resultado(contagem[0], contagem[1], contagem[2], rej, ok);
    }

    /** Usado também pela consulta à SEFAZ: uma nota de uma empresa já conhecida. */
    public Optional<NotaDto> importarUma(Long tenantId, Long usuarioId, Cliente cliente, String nome, byte[] xml, String origem) {
        int[] c = new int[3];
        List<NotaDto> ok = new ArrayList<>();
        List<Rejeitado> rej = new ArrayList<>();
        uma(tenantId, usuarioId, cliente, null, nome, xml, origem, c, ok, rej);
        if (!rej.isEmpty()) throw new NegocioException(rej.get(0).motivo());
        return ok.stream().findFirst();
    }

    private void uma(Long tenantId, Long usuarioId, Cliente alvo, Long setorId, String nome, byte[] xml, String origem, int[] contagem, List<NotaDto> ok, List<Rejeitado> rej) {
        contagem[0]++;
        String chaveArquivo = null;
        try {
            if (xml.length > MAX_XML) throw new NegocioException("XML maior que 5 MB");
            documentoService.validarBytes(nome.toLowerCase().endsWith(".xml") ? nome : nome + ".xml", xml);
            NotaParseada n = XmlFiscalParser.ler(xml);
            Cliente c = alvo != null ? alvo : descobrir(tenantId, n);
            if (c.getCnpj() == null || (!c.getCnpj().equals(n.cnpjEmitente()) && !c.getCnpj().equals(n.cnpjDestinatario()))) {
                throw new NegocioException(alvo != null ? "A nota não é da empresa " + c.getNome() + " (o CNPJ dela não é emitente nem destinatário)" : "Nenhuma empresa atendida tem o CNPJ desta nota");
            }
            if (notas.existsByTenantIdAndChaveAcesso(tenantId, n.chave())) { contagem[2]++; return; }
            int ano = n.dataEmissao() == null ? br.com.viriato.comum.Datas.hoje().getYear() : n.dataEmissao().getYear();
            int mes = n.dataEmissao() == null ? br.com.viriato.comum.Datas.hoje().getMonthValue() : n.dataEmissao().getMonthValue();
            String arquivo = nome.toLowerCase().endsWith(".xml") ? nome : nome + ".xml";
            chaveArquivo = arvore.chave(tenantId, c.getId(), ano, mes, setorId, null, arquivo);
            storage.salvar(chaveArquivo, new ByteArrayInputStream(xml), xml.length, "application/xml");
            final String chaveFinal = chaveArquivo;
            NotaDto dto = novaTransacao(() -> {
                Documento d = documentos.save(ged.novo(tenantId, c.getId(), setorId, ano, mes, "XML", true, arquivo, chaveFinal, "application/xml", xml.length, usuarioId));
                NotaFiscalXml x = new NotaFiscalXml();
                x.setTenantId(tenantId);
                x.setClienteId(c.getId());
                x.setDocumentoId(d.getId());
                x.setChaveAcesso(n.chave());
                x.setModelo(n.modelo());
                x.setNumeroNota(n.numero());
                x.setSerie(n.serie());
                x.setDataEmissao(n.dataEmissao());
                x.setCompetenciaAno(n.dataEmissao() == null ? null : ano);
                x.setCompetenciaMes(n.dataEmissao() == null ? null : mes);
                x.setCnpjEmitente(n.cnpjEmitente());
                x.setNomeEmitente(corta(n.nomeEmitente(), 200));
                x.setCnpjDestinatario(n.cnpjDestinatario());
                x.setNomeDestinatario(corta(n.nomeDestinatario(), 200));
                x.setTipoOperacao(c.getCnpj().equals(n.cnpjEmitente()) ? "SAIDA" : "ENTRADA");   // a empresa é quem emitiu (saída) ou quem recebeu (entrada)
                x.setValorTotal(n.valorTotal());
                x.setValorIcms(n.icms());
                x.setValorIpi(n.ipi());
                x.setValorPis(n.pis());
                x.setValorCofins(n.cofins());
                x.setValorIss(n.iss());
                x.setCfops(lista(n.cfops()));
                x.setNcms(lista(n.ncms()));
                x.setOrigem(origem);
                return dto(notas.save(x));
            });
            contagem[1]++;
            ok.add(dto);
        } catch (NegocioException e) {
            if (chaveArquivo != null) storage.remover(chaveArquivo);
            rej.add(new Rejeitado(nome, e.getMessage()));
        } catch (RuntimeException e) {
            if (chaveArquivo != null) storage.remover(chaveArquivo);
            rej.add(new Rejeitado(nome, "Não foi possível gravar a nota"));
        }
    }

    private Cliente descobrir(Long tenantId, NotaParseada n) {
        for (String cnpj : new String[]{n.cnpjEmitente(), n.cnpjDestinatario()}) {
            if (cnpj == null) continue;
            Optional<Cliente> c = clientes.findByTenantIdAndCnpj(tenantId, cnpj);
            if (c.isPresent()) return c.get();
        }
        throw new NegocioException("Nenhuma empresa atendida tem o CNPJ desta nota");
    }

    private <T> T novaTransacao(java.util.function.Supplier<T> acao) {
        TransactionTemplate tx = new TransactionTemplate(tm);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tx.execute(s -> acao.get());
    }

    /** Percorre o .zip sem gravar em disco, com limites de quantidade e de tamanho (contra zip bomb). */
    static void lerZip(byte[] zip, String nomeZip, java.util.function.BiConsumer<String, byte[]> porXml) throws IOException {
        long total = 0;
        int entradas = 0;
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                if (++entradas > MAX_ENTRADAS) throw new NegocioException("O .zip tem mais de " + MAX_ENTRADAS + " arquivos");
                String nome = GedService.limpar(e.getName());
                if (e.getName().startsWith("__MACOSX") || !nome.toLowerCase().endsWith(".xml")) continue;
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16 * 1024];
                long deste = 0;
                int n;
                while ((n = z.read(buf)) > 0) {
                    deste += n;
                    total += n;
                    if (deste > MAX_XML) break;
                    if (total > MAX_TOTAL) throw new NegocioException("O conteúdo do .zip passa de 100 MB descompactado");
                    out.write(buf, 0, n);
                }
                if (deste > MAX_XML) { porXml.accept(nome, new byte[(int) MAX_XML + 1]); continue; }   // vira "XML maior que 5 MB"
                porXml.accept(nome, out.toByteArray());
            }
        }
    }

    // ---------- consulta ----------
    public List<NotaDto> listar(Long tenantId, Long clienteId, Integer ano, Integer mes, String tipo) {
        clientes.findByIdAndTenantId(clienteId, tenantId).orElseThrow(() -> new NaoEncontradoException("Empresa"));
        return notas.buscar(tenantId, clienteId, ano, mes, tipo == null || tipo.isBlank() ? null : tipo.toUpperCase(), org.springframework.data.domain.PageRequest.of(0, 500)).stream().map(this::dto).toList();
    }

    private NotaDto dto(NotaFiscalXml x) {
        return new NotaDto(x.getId(), x.getChaveAcesso(), x.getModelo(), x.getNumeroNota(), x.getSerie(), x.getDataEmissao(), x.getCompetenciaAno(), x.getCompetenciaMes(), x.getCnpjEmitente(),
                x.getNomeEmitente(), x.getCnpjDestinatario(), x.getNomeDestinatario(), x.getTipoOperacao(), x.getValorTotal(), x.getValorIcms(), x.getValorIpi(), x.getValorPis(), x.getValorCofins(),
                x.getValorIss(), leitura(x.getCfops()), leitura(x.getNcms()), x.getOrigem(), x.getDocumentoId());
    }

    private String lista(java.util.Collection<String> c) { try { return c == null || c.isEmpty() ? null : json.writeValueAsString(c); } catch (Exception e) { return null; } }
    @SuppressWarnings("unchecked")
    private List<String> leitura(String j) { try { return j == null ? List.of() : json.readValue(j, List.class); } catch (Exception e) { return List.of(); } }
    private static String corta(String s, int max) { return s == null ? null : s.length() > max ? s.substring(0, max) : s; }
}
