package br.com.viriato.crm;

import br.com.viriato.agente.Agente;
import br.com.viriato.agente.AgenteRepository;
import br.com.viriato.agente.CertificadoAgente;
import br.com.viriato.agente.CertificadoAgenteRepository;
import br.com.viriato.auditoria.AuditoriaService;
import br.com.viriato.cadastro.Cliente;
import br.com.viriato.cadastro.ClienteRepository;
import br.com.viriato.comum.Datas;
import br.com.viriato.comum.NaoEncontradoException;
import br.com.viriato.comum.NegocioException;
import br.com.viriato.seguranca.UsuarioLogado;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.security.auth.x500.X500Principal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Certificados digitais lidos dos computadores pelo agente local. O sistema só guarda os dados públicos (titular, emissora,
 * série, validade e a impressão digital); a chave privada fica no repositório do Windows (ou no token) do computador e quem
 * assina é o agente. Aqui os certificados são reconhecidos pelo CNPJ do titular e ligados à empresa atendida.
 */
@Service
public class CertificadoService {
    private static final Pattern CNPJ_NO_TITULAR = Pattern.compile("(?<!\\d)(\\d{14})(?!\\d)");
    static final int MAXIMO_POR_CONTATO = 300;

    public enum Situacao { VALIDO, VENCENDO, VENCIDO, SEM_VALIDADE }

    /** O que a tela mostra: nada que permita usar o certificado. */
    public record CertificadoDto(Long id, String titular, String cnpjCertificado, String emissora, String numeroSerie, LocalDate validoDe, LocalDate validoAte,
                                 Situacao situacao, Long diasParaVencer, boolean presente, Long clienteId, String clienteNome, boolean vinculoManual,
                                 List<String> computadores, int computadoresConectados) {}

    public record Vinculo(Long clienteId) {}

    private final ClienteCertificadoRepository certs;
    private final CertificadoAgenteRepository ligacoes;
    private final AgenteRepository agentes;
    private final ClienteRepository clientes;
    private final AuditoriaService auditoria;
    private final Duration conectado;

    public CertificadoService(ClienteCertificadoRepository certs, CertificadoAgenteRepository ligacoes, AgenteRepository agentes, ClienteRepository clientes,
                              AuditoriaService auditoria, @org.springframework.beans.factory.annotation.Value("${viriato.agente.conectado-segundos:45}") long conectadoSegundos) {
        this.certs = certs;
        this.ligacoes = ligacoes;
        this.agentes = agentes;
        this.clientes = clientes;
        this.auditoria = auditoria;
        this.conectado = Duration.ofSeconds(conectadoSegundos);
    }

    public Duration janelaDeConexao() { return conectado; }

    @Transactional(readOnly = true)
    public List<CertificadoDto> listar(Long t, Long clienteId) {
        return montar(t, certs.findByClienteIdAndTenantIdOrderByIdDesc(clienteId, t));
    }

    @Transactional(readOnly = true)
    public List<CertificadoDto> listarTodos(Long t) { return montar(t, certs.findByTenantIdOrderByTitular(t)); }

    @Transactional
    public CertificadoDto vincular(UsuarioLogado quem, Long id, Long clienteId) {
        Long t = quem.tenantId();
        ClienteCertificado c = certs.findByIdAndTenantId(id, t).orElseThrow(() -> new NaoEncontradoException("Certificado"));
        String destino = "nenhuma empresa";
        if (clienteId != null) {
            Cliente cli = clientes.findByIdAndTenantId(clienteId, t).orElseThrow(() -> new NaoEncontradoException("Empresa"));
            destino = cli.getNome();
        }
        c.setClienteId(clienteId);
        c.setVinculoManual(true);
        c = certs.save(c);
        auditoria.registrar(t, quem.id(), "CERTIFICADO_VINCULADO", "Certificado série " + c.getNumeroSerie() + " ligado a " + destino);
        return montar(t, List.of(c)).get(0);
    }

    /** Desfaz os vínculos de uma empresa que está sendo apagada. */
    @Transactional
    public void desvincularDaEmpresa(Long tenantId, Long clienteId) { certs.desvincularDaEmpresa(tenantId, clienteId); }

    /**
     * Recebe do agente a lista completa de certificados (com chave privada) do computador dele. Cada um é lido de novo aqui
     * (o agente não é fonte de verdade dos dados), reconhecido pelo CNPJ e ligado à empresa. O que sumiu do computador deixa
     * de contar nele; sem nenhum computador, o certificado fica como ausente.
     */
    @Transactional
    public int sincronizar(Agente agente, List<String> certificadosBase64) {
        Long t = agente.getTenantId();
        Instant agora = Instant.now();
        Map<String, X509Certificate> lidos = new LinkedHashMap<>();
        for (String b64 : certificadosBase64.stream().limit(MAXIMO_POR_CONTATO).toList()) {
            try {
                X509Certificate x = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(Base64.getMimeDecoder().decode(b64)));
                lidos.putIfAbsent(impressao(x), x);
            } catch (Exception e) {
                // certificado que não se lê é ignorado
            }
        }
        List<Cliente> empresas = null;
        int novos = 0;
        Map<Long, CertificadoAgente> atuais = new HashMap<>();
        for (CertificadoAgente l : ligacoes.findByAgenteId(agente.getId())) atuais.put(l.getCertificadoId(), l);
        Set<Long> vistos = new java.util.HashSet<>();

        for (Map.Entry<String, X509Certificate> e : lidos.entrySet()) {
            X509Certificate x = e.getValue();
            ClienteCertificado c = certs.findByTenantIdAndThumbprint(t, e.getKey()).orElse(null);
            if (c == null) {
                c = new ClienteCertificado();
                c.setTenantId(t);
                c.setThumbprint(e.getKey());
                novos++;
            }
            String titular = x.getSubjectX500Principal().getName(X500Principal.RFC2253);
            c.setTitular(corta(x.getSubjectX500Principal().getName(X500Principal.RFC1779), 255));
            c.setCnpjCertificado(cnpjDo(titular));
            c.setEmissora(corta(x.getIssuerX500Principal().getName(X500Principal.RFC1779), 255));
            c.setNumeroSerie(corta(x.getSerialNumber().toString(16).toUpperCase(), 100));
            c.setValidoDe(Datas.paraData(x.getNotBefore()));
            c.setValidoAte(Datas.paraData(x.getNotAfter()));
            c.setAtivo(true);
            c.setVistoEm(agora);
            if (c.getClienteId() == null && !c.isVinculoManual() && c.getCnpjCertificado() != null) {
                if (empresas == null) empresas = clientes.findByTenantIdOrderByNome(t);
                c.setClienteId(empresaDoCnpj(empresas, c.getCnpjCertificado()));
            }
            c = certs.save(c);
            vistos.add(c.getId());
            CertificadoAgente l = atuais.get(c.getId());
            if (l == null) { l = new CertificadoAgente(); l.setCertificadoId(c.getId()); l.setAgenteId(agente.getId()); }
            l.setVistoEm(agora);
            ligacoes.save(l);
        }

        for (Map.Entry<Long, CertificadoAgente> a : atuais.entrySet()) {
            if (vistos.contains(a.getKey())) continue;
            ligacoes.delete(a.getValue());   // saiu deste computador
            if (ligacoes.findByCertificadoId(a.getKey()).stream().allMatch(x -> x.getAgenteId().equals(agente.getId()))) {
                certs.findById(a.getKey()).ifPresent(c -> { c.setAtivo(false); certs.save(c); });   // não está mais em nenhum computador
            }
        }
        if (novos > 0) auditoria.registrar(t, agente.getUsuarioId(), "CERTIFICADOS_DETECTADOS", novos + " certificado(s) novo(s) lido(s) do computador " + agente.getNome());
        return novos;
    }

    /** O CNPJ completo manda; sem ele, a raiz (8 dígitos) só vale se apontar para uma empresa só. */
    static Long empresaDoCnpj(List<Cliente> empresas, String cnpj) {
        for (Cliente c : empresas) if (cnpj.equals(c.getCnpj())) return c.getId();
        List<Cliente> mesmaRaiz = empresas.stream().filter(c -> c.getCnpj() != null && c.getCnpj().substring(0, 8).equals(cnpj.substring(0, 8))).toList();
        return mesmaRaiz.size() == 1 ? mesmaRaiz.get(0).getId() : null;
    }

    /**
     * O certificado que o sistema usa para trabalhar pela empresa: instalado em algum computador, dentro da validade e,
     * de preferência, num computador com o agente conectado agora.
     */
    @Transactional(readOnly = true)
    public ClienteCertificado paraUso(Long tenantId, Long clienteId) {
        LocalDate hoje = Datas.hoje();
        List<ClienteCertificado> candidatos = certs.findByClienteIdAndTenantIdOrderByIdDesc(clienteId, tenantId).stream()
                .filter(c -> c.isAtivo() && (c.getValidoAte() == null || !c.getValidoAte().isBefore(hoje))).toList();
        if (candidatos.isEmpty()) throw new NegocioException("A empresa não tem certificado digital válido lido por um agente. Instale o agente no computador onde o certificado está e confira em Certificados");
        Instant desde = Instant.now().minus(conectado);
        return candidatos.stream().filter(c -> ligacoes.agentesConectados(c.getId(), desde) > 0).findFirst()
                .orElseThrow(() -> new NegocioException("O certificado da empresa está em um computador com o agente desconectado. Abra o agente nesse computador e tente de novo"));
    }

    @Transactional(readOnly = true)
    public ClienteCertificado porId(Long id) { return certs.findById(id).orElseThrow(() -> new NaoEncontradoException("Certificado")); }

    /** Registra na auditoria que o sistema usou o certificado (a chave privada continua no computador do usuário). */
    public void registrarUso(Long tenantId, Long usuarioId, ClienteCertificado c, String finalidade) {
        auditoria.registrar(tenantId, usuarioId, "CERTIFICADO_USADO", "Certificado série " + c.getNumeroSerie() + " usado para: " + finalidade);
    }

    // ---------- leitura ----------

    private List<CertificadoDto> montar(Long t, List<ClienteCertificado> lista) {
        if (lista.isEmpty()) return List.of();
        Map<Long, String> nomes = clientes.findByTenantIdOrderByNome(t).stream().collect(Collectors.toMap(Cliente::getId, Cliente::getNome));
        Map<Long, List<CertificadoAgente>> porCert = ligacoes.findByCertificadoIdIn(lista.stream().map(ClienteCertificado::getId).toList()).stream().collect(Collectors.groupingBy(CertificadoAgente::getCertificadoId));
        Map<Long, Agente> maquinas = agentes.findAllById(porCert.values().stream().flatMap(List::stream).map(CertificadoAgente::getAgenteId).collect(Collectors.toSet())).stream()
                .filter(a -> !a.isRevogado() && a.getTenantId().equals(t)).collect(Collectors.toMap(Agente::getId, a -> a));
        Instant desde = Instant.now().minus(conectado);
        List<CertificadoDto> r = new ArrayList<>();
        for (ClienteCertificado c : lista) {
            List<Agente> daqui = porCert.getOrDefault(c.getId(), List.of()).stream().map(l -> maquinas.get(l.getAgenteId())).filter(java.util.Objects::nonNull).toList();
            Long dias = c.getValidoAte() == null ? null : java.time.temporal.ChronoUnit.DAYS.between(Datas.hoje(), c.getValidoAte());
            Situacao s = dias == null ? Situacao.SEM_VALIDADE : dias < 0 ? Situacao.VENCIDO : dias <= 30 ? Situacao.VENCENDO : Situacao.VALIDO;
            r.add(new CertificadoDto(c.getId(), c.getTitular(), c.getCnpjCertificado(), c.getEmissora(), c.getNumeroSerie(), c.getValidoDe(), c.getValidoAte(), s, dias,
                    c.isAtivo(), c.getClienteId(), c.getClienteId() == null ? null : nomes.get(c.getClienteId()), c.isVinculoManual(),
                    daqui.stream().map(Agente::getNome).sorted().toList(), (int) daqui.stream().filter(a -> a.getUltimoContato() != null && a.getUltimoContato().isAfter(desde)).count()));
        }
        return r;
    }

    static String impressao(X509Certificate x) throws Exception {
        return HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-1").digest(x.getEncoded()));
    }

    /** Certificados ICP-Brasil trazem o CNPJ no nome do titular (ex.: CN=EMPRESA LTDA:12345678000195). */
    static String cnpjDo(String titular) {
        Matcher m = CNPJ_NO_TITULAR.matcher(titular);
        while (m.find()) if (Documentos.cnpjValido(m.group(1))) return m.group(1);
        return null;
    }

    private static String corta(String s, int max) { return s.length() > max ? s.substring(0, max) : s; }
}
