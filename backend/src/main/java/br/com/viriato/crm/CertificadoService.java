package br.com.viriato.crm;

import br.com.viriato.auditoria.AuditoriaService;
import br.com.viriato.cadastro.Cliente;
import br.com.viriato.cadastro.ClienteRepository;
import br.com.viriato.comum.Datas;
import br.com.viriato.comum.NaoEncontradoException;
import br.com.viriato.comum.NegocioException;
import br.com.viriato.crm.Enums.TipoCertificado;
import br.com.viriato.documento.ArmazenamentoArquivos;
import br.com.viriato.documento.DocumentoService;
import br.com.viriato.seguranca.Cifra;
import br.com.viriato.seguranca.UsuarioLogado;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.UnrecoverableKeyException;
import java.security.cert.X509Certificate;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.security.auth.x500.X500Principal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Cofre de certificados digitais. A1: o .pfx é aberto com {@link KeyStore} na hora do envio (valida a senha e extrai titular,
 * emissora, série e validade), o arquivo é guardado cifrado (AES-256-GCM) no armazenamento e a senha cifrada no banco. Nada
 * disso sai pela API, e cada uso do certificado fica na auditoria. A3: registro informativo (o token não é do sistema).
 */
@Service
public class CertificadoService {
    private static final Pattern CNPJ_NO_TITULAR = Pattern.compile("(?<!\\d)(\\d{14})(?!\\d)");
    static final int MAXIMO_BYTES = 1024 * 1024;

    public enum Situacao { VALIDO, VENCENDO, VENCIDO, SEM_VALIDADE }

    /** Sem arquivo e sem senha: só o que a tela precisa para acompanhar. */
    public record CertificadoDto(Long id, TipoCertificado tipo, String titular, String cnpjCertificado, String emissora, String numeroSerie,
                                 LocalDate validoDe, LocalDate validoAte, Situacao situacao, Long diasParaVencer, boolean ativo, String observacao) {}

    public record NovoA3(String titular, String emissora, String numeroSerie, LocalDate validoDe, LocalDate validoAte, String observacao) {}
    public record AtualizaCertificado(Boolean ativo, String observacao) {}

    /** O que o conector (por exemplo, o da SEFAZ) recebe para assinar; a senha nunca é entregue. */
    public record CertificadoEmUso(PrivateKey chave, X509Certificate[] cadeia) {}

    private final ClienteCertificadoRepository certs;
    private final ClienteRepository clientes;
    private final ArmazenamentoArquivos storage;
    private final Cifra cifra;
    private final DocumentoService documentos;
    private final AuditoriaService auditoria;

    public CertificadoService(ClienteCertificadoRepository certs, ClienteRepository clientes, ArmazenamentoArquivos storage, Cifra cifra,
                              DocumentoService documentos, AuditoriaService auditoria) {
        this.certs = certs;
        this.clientes = clientes;
        this.storage = storage;
        this.cifra = cifra;
        this.documentos = documentos;
        this.auditoria = auditoria;
    }

    @Transactional(readOnly = true)
    public List<CertificadoDto> listar(Long t, Long clienteId) {
        return certs.findByClienteIdAndTenantIdOrderByIdDesc(clienteId, t).stream().map(CertificadoService::dto).toList();
    }

    @Transactional
    public CertificadoDto enviarA1(UsuarioLogado quem, Long clienteId, MultipartFile arquivo, String senha, String observacao) throws IOException {
        Long t = quem.tenantId();
        Cliente cli = clientes.findByIdAndTenantId(clienteId, t).orElseThrow(() -> new NaoEncontradoException("Empresa"));
        if (arquivo == null || arquivo.isEmpty()) throw new NegocioException("Envie o arquivo do certificado (.pfx ou .p12)");
        if (senha == null || senha.isEmpty()) throw new NegocioException("Informe a senha do certificado");
        String ext = br.com.viriato.documento.ValidadorDeArquivo.extensao(arquivo.getOriginalFilename());
        if (!ext.equals("pfx") && !ext.equals("p12")) throw new NegocioException("O certificado A1 é um arquivo .pfx ou .p12");
        if (arquivo.getSize() > MAXIMO_BYTES) throw new NegocioException("O arquivo do certificado passa de 1 MB");
        documentos.validarConteudo(arquivo.getOriginalFilename(), arquivo);   // conteúdo do tipo certo e antivírus
        byte[] pfx = arquivo.getBytes();

        X509Certificate x = ler(pfx, senha);
        LocalDate de = Datas.paraData(x.getNotBefore()), ate = Datas.paraData(x.getNotAfter());
        if (ate.isBefore(Datas.hoje())) throw new NegocioException("O certificado já venceu em " + Datas.DATA.format(ate));
        String titular = x.getSubjectX500Principal().getName(X500Principal.RFC2253);
        String cnpjCert = cnpjDo(titular);
        if (cnpjCert != null && cli.getCnpj() != null && !cnpjCert.substring(0, 8).equals(cli.getCnpj().substring(0, 8))) {
            throw new NegocioException("O certificado é do CNPJ " + Documentos.formatarCnpj(cnpjCert) + ", diferente do CNPJ desta empresa");
        }

        String chave = "tenant-%d/cliente-%d/certificados/%s.pfx.enc".formatted(t, clienteId, UUID.randomUUID());
        byte[] cifrado = cifra.cifrar(Base64.getEncoder().encodeToString(pfx)).getBytes(StandardCharsets.UTF_8);
        storage.salvar(chave, new ByteArrayInputStream(cifrado), cifrado.length, "application/octet-stream");

        ClienteCertificado c = new ClienteCertificado();
        c.setTenantId(t);
        c.setClienteId(clienteId);
        c.setTipo(TipoCertificado.A1);
        c.setTitular(corta(x.getSubjectX500Principal().getName(X500Principal.RFC1779), 255));
        c.setCnpjCertificado(cnpjCert);
        c.setEmissora(corta(x.getIssuerX500Principal().getName(X500Principal.RFC1779), 255));
        c.setNumeroSerie(x.getSerialNumber().toString(16).toUpperCase());
        c.setValidoDe(de);
        c.setValidoAte(ate);
        c.setArquivoChave(chave);
        c.setSenhaCifrada(cifra.cifrar(senha));
        c.setObservacao(observacao == null || observacao.isBlank() ? null : corta(observacao.trim(), 300));
        c.setCriadoPor(quem.id());
        try {
            c = certs.save(c);
        } catch (RuntimeException e) {
            storage.remover(chave);
            throw e;
        }
        auditoria.registrar(t, quem.id(), "CERTIFICADO_CADASTRADO", "A1 da empresa " + cli.getNome() + ", série " + c.getNumeroSerie());
        return dto(c);
    }

    @Transactional
    public CertificadoDto registrarA3(UsuarioLogado quem, Long clienteId, NovoA3 r) {
        Long t = quem.tenantId();
        Cliente cli = clientes.findByIdAndTenantId(clienteId, t).orElseThrow(() -> new NaoEncontradoException("Empresa"));
        if (r.titular() == null || r.titular().isBlank()) throw new NegocioException("Informe o titular do certificado");
        if (r.validoAte() == null) throw new NegocioException("Informe a data de validade");
        if (r.validoDe() != null && r.validoDe().isAfter(r.validoAte())) throw new NegocioException("A validade termina antes de começar");
        ClienteCertificado c = new ClienteCertificado();
        c.setTenantId(t);
        c.setClienteId(clienteId);
        c.setTipo(TipoCertificado.A3);
        c.setTitular(corta(r.titular().trim(), 255));
        c.setEmissora(r.emissora() == null || r.emissora().isBlank() ? null : corta(r.emissora().trim(), 255));
        c.setNumeroSerie(r.numeroSerie() == null || r.numeroSerie().isBlank() ? null : corta(r.numeroSerie().trim(), 100));
        c.setValidoDe(r.validoDe());
        c.setValidoAte(r.validoAte());
        c.setObservacao(r.observacao() == null || r.observacao().isBlank() ? null : corta(r.observacao().trim(), 300));
        c.setCriadoPor(quem.id());
        c = certs.save(c);
        auditoria.registrar(t, quem.id(), "CERTIFICADO_CADASTRADO", "A3 da empresa " + cli.getNome());
        return dto(c);
    }

    @Transactional
    public CertificadoDto alterar(UsuarioLogado quem, Long clienteId, Long id, AtualizaCertificado r) {
        ClienteCertificado c = certs.findByIdAndClienteIdAndTenantId(id, clienteId, quem.tenantId()).orElseThrow(() -> new NaoEncontradoException("Certificado"));
        if (r.ativo() != null) c.setAtivo(r.ativo());
        if (r.observacao() != null) c.setObservacao(r.observacao().isBlank() ? null : corta(r.observacao().trim(), 300));
        return dto(certs.save(c));
    }

    @Transactional
    public void apagar(UsuarioLogado quem, Long clienteId, Long id) {
        ClienteCertificado c = certs.findByIdAndClienteIdAndTenantId(id, clienteId, quem.tenantId()).orElseThrow(() -> new NaoEncontradoException("Certificado"));
        certs.delete(c);
        if (c.getArquivoChave() != null) storage.remover(c.getArquivoChave());
        auditoria.registrar(quem.tenantId(), quem.id(), "CERTIFICADO_REMOVIDO", "Certificado " + c.getTipo() + " série " + c.getNumeroSerie());
    }

    /**
     * Abre o certificado A1 para uso por um conector (ex.: consulta à SEFAZ). Registra na auditoria quem usou e para quê.
     * Não é exposto por nenhuma rota.
     */
    @Transactional
    public CertificadoEmUso abrirParaUso(Long tenantId, Long usuarioId, Long certificadoId, String finalidade) {
        ClienteCertificado c = certs.findById(certificadoId).filter(x -> x.getTenantId().equals(tenantId)).orElseThrow(() -> new NaoEncontradoException("Certificado"));
        if (c.getTipo() != TipoCertificado.A1 || c.getArquivoChave() == null) throw new NegocioException("Só o certificado A1 pode ser usado pelo sistema");
        if (!c.isAtivo()) throw new NegocioException("O certificado está desativado");
        if (c.getValidoAte() != null && c.getValidoAte().isBefore(Datas.hoje())) throw new NegocioException("O certificado está vencido");
        try {
            byte[] cifrado = storage.abrir(c.getArquivoChave()).readAllBytes();
            byte[] pfx = Base64.getDecoder().decode(cifra.abrir(new String(cifrado, StandardCharsets.UTF_8)));
            String senha = cifra.abrir(c.getSenhaCifrada());
            KeyStore ks = abrir(pfx, senha);
            for (String alias : Collections.list(ks.aliases())) {
                if (!ks.isKeyEntry(alias)) continue;
                PrivateKey chave = (PrivateKey) ks.getKey(alias, senha.toCharArray());
                java.security.cert.Certificate[] cadeia = ks.getCertificateChain(alias);
                X509Certificate[] x = new X509Certificate[cadeia.length];
                for (int i = 0; i < cadeia.length; i++) x[i] = (X509Certificate) cadeia[i];
                auditoria.registrar(tenantId, usuarioId, "CERTIFICADO_USADO", "Certificado série " + c.getNumeroSerie() + " usado para: " + finalidade);
                return new CertificadoEmUso(chave, x);
            }
        } catch (IOException | java.security.GeneralSecurityException e) {
            throw new NegocioException("Não foi possível abrir o certificado guardado");
        }
        throw new NegocioException("O certificado guardado não tem chave privada");
    }

    /** Apaga os arquivos do cofre de uma empresa que está sendo apagada. */
    public void removerArquivosDaEmpresa(Long tenantId, Long clienteId) {
        for (ClienteCertificado c : certs.findByClienteIdAndTenantIdOrderByIdDesc(clienteId, tenantId)) {
            if (c.getArquivoChave() != null) storage.remover(c.getArquivoChave());
        }
    }

    // ---------- leitura do .pfx ----------

    private static KeyStore abrir(byte[] pfx, String senha) throws IOException, java.security.GeneralSecurityException {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(new ByteArrayInputStream(pfx), senha.toCharArray());
        return ks;
    }

    private static X509Certificate ler(byte[] pfx, String senha) {
        try {
            KeyStore ks = abrir(pfx, senha);
            for (String alias : Collections.list(ks.aliases())) {
                if (ks.isKeyEntry(alias) && ks.getCertificate(alias) instanceof X509Certificate x) return x;
            }
            throw new NegocioException("O arquivo não tem um certificado com chave privada");
        } catch (IOException e) {
            if (e.getCause() instanceof UnrecoverableKeyException || (e.getMessage() != null && e.getMessage().toLowerCase().contains("password"))) {
                throw new NegocioException("A senha do certificado está incorreta");
            }
            throw new NegocioException("O arquivo não é um certificado válido (.pfx ou .p12)");
        } catch (java.security.GeneralSecurityException e) {
            throw new NegocioException("O arquivo não é um certificado válido (.pfx ou .p12)");
        }
    }

    /** Certificados ICP-Brasil trazem o CNPJ no nome do titular (ex.: CN=EMPRESA LTDA:12345678000195). */
    static String cnpjDo(String titular) {
        Matcher m = CNPJ_NO_TITULAR.matcher(titular);
        while (m.find()) if (Documentos.cnpjValido(m.group(1))) return m.group(1);
        return null;
    }

    static CertificadoDto dto(ClienteCertificado c) {
        Long dias = c.getValidoAte() == null ? null : java.time.temporal.ChronoUnit.DAYS.between(Datas.hoje(), c.getValidoAte());
        Situacao s = dias == null ? Situacao.SEM_VALIDADE : dias < 0 ? Situacao.VENCIDO : dias <= 30 ? Situacao.VENCENDO : Situacao.VALIDO;
        return new CertificadoDto(c.getId(), c.getTipo(), c.getTitular(), c.getCnpjCertificado(), c.getEmissora(), c.getNumeroSerie(),
                c.getValidoDe(), c.getValidoAte(), s, dias, c.isAtivo(), c.getObservacao());
    }

    private static String corta(String s, int max) { return s.length() > max ? s.substring(0, max) : s; }
}
