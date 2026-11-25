package br.com.viriato.agente;

import br.com.viriato.auditoria.AuditoriaService;
import br.com.viriato.comum.NaoEncontradoException;
import br.com.viriato.comum.NegocioException;
import br.com.viriato.crm.CertificadoService;
import br.com.viriato.crm.ClienteCertificado;
import br.com.viriato.seguranca.UsuarioLogado;
import br.com.viriato.usuario.Papel;
import br.com.viriato.usuario.Usuario;
import br.com.viriato.usuario.UsuarioRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * O agente é um programa pequeno no computador de cada usuário. Ele lê os certificados instalados ali (repositório do Windows,
 * inclusive token/cartão A3), avisa o Viriato quais são e executa, no próprio computador, o que exige a chave privada (hoje, a
 * chamada com TLS mútuo à SEFAZ). A chave nunca sai da máquina. A ligação é sempre iniciada pelo agente (ele consulta o Viriato a
 * cada poucos segundos), então o Viriato pode estar em um servidor ou em Docker sem enxergar o computador do usuário.
 */
@Service
public class AgenteService {
    private static final Logger log = LoggerFactory.getLogger(AgenteService.class);
    private static final SecureRandom ALEATORIO = new SecureRandom();
    private static final String LETRAS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";   // sem 0/O/1/I, para ditar e digitar sem erro
    static final Duration VALIDADE_DO_CODIGO = Duration.ofMinutes(10);
    static final int MAXIMO_RESPOSTA = 8 * 1024 * 1024;

    public record Pareamento(String codigo, Instant expiraEm) {}
    public record AgenteDto(Long id, String nome, String versao, Long usuarioId, String usuario, Instant ultimoContato, boolean conectado, int certificados) {}
    public record Tarefa(Long id, String tipo, String url, String contentType, String corpo, String thumbprint) {}
    public record Contato(String versao, List<String> certificados) {}
    public record ContatoResposta(List<Tarefa> tarefas) {}
    public record Resultado(Integer httpStatus, String corpo, String erro) {}

    private final AgenteRepository agentes;
    private final AgentePareamentoRepository pareamentos;
    private final AgenteTarefaRepository tarefas;
    private final CertificadoAgenteRepository ligacoes;
    private final CertificadoService certificados;
    private final UsuarioRepository usuarios;
    private final AuditoriaService auditoria;
    private final long esperaSegundos;

    public AgenteService(AgenteRepository agentes, AgentePareamentoRepository pareamentos, AgenteTarefaRepository tarefas, CertificadoAgenteRepository ligacoes,
                         CertificadoService certificados, UsuarioRepository usuarios, AuditoriaService auditoria,
                         @Value("${viriato.agente.espera-segundos:90}") long esperaSegundos) {
        this.agentes = agentes;
        this.pareamentos = pareamentos;
        this.tarefas = tarefas;
        this.ligacoes = ligacoes;
        this.certificados = certificados;
        this.usuarios = usuarios;
        this.auditoria = auditoria;
        this.esperaSegundos = esperaSegundos;
    }

    // ---------- pareamento (feito pelo usuário, na tela) ----------

    @Transactional
    public Pareamento gerarCodigo(UsuarioLogado quem) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) sb.append(LETRAS.charAt(ALEATORIO.nextInt(LETRAS.length())));
        String codigo = sb.substring(0, 4) + "-" + sb.substring(4);
        AgentePareamento p = new AgentePareamento();
        p.setTenantId(quem.tenantId());
        p.setUsuarioId(quem.id());
        p.setCodigoHash(hash(normalizar(codigo)));
        p.setExpiraEm(Instant.now().plus(VALIDADE_DO_CODIGO));
        pareamentos.save(p);
        return new Pareamento(codigo, p.getExpiraEm());
    }

    /** Chamada pelo agente (sem login): troca o código de uso único pelo token do agente. */
    @Transactional
    public String parear(String codigo, String nome, String versao) {
        String normal = codigo == null ? "" : normalizar(codigo);
        AgentePareamento p = normal.length() == 8 ? pareamentos.findByCodigoHash(hash(normal)).orElse(null) : null;
        if (p == null || p.isUsado() || p.getExpiraEm().isBefore(Instant.now()) || pareamentos.consumir(p.getId()) != 1) {
            throw new NegocioException("Código inválido ou expirado. Gere um novo código no Viriato (Certificados e agente)");
        }
        Usuario u = usuarios.findByIdAndTenantId(p.getUsuarioId(), p.getTenantId()).filter(Usuario::isAtivo).orElseThrow(() -> new NegocioException("Código inválido ou expirado. Gere um novo código no Viriato (Certificados e agente)"));
        byte[] bruto = new byte[32];
        ALEATORIO.nextBytes(bruto);
        String token = "vagt_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bruto);
        Agente a = new Agente();
        a.setTenantId(p.getTenantId());
        a.setUsuarioId(u.getId());
        a.setNome(corta(nome == null || nome.isBlank() ? "Computador sem nome" : nome.trim(), 120));
        a.setVersao(versao == null ? null : corta(versao.trim(), 30));
        a.setTokenHash(hash(token));
        a = agentes.save(a);
        auditoria.registrar(a.getTenantId(), u.getId(), "AGENTE_PAREADO", "Agente instalado em " + a.getNome());
        return token;
    }

    @Transactional(readOnly = true)
    public List<AgenteDto> listar(UsuarioLogado quem) {
        boolean gestao = quem.papel() == Papel.ADMIN || quem.papel() == Papel.GESTOR;
        List<Agente> lista = gestao ? agentes.findByTenantIdAndRevogadoFalseOrderByNome(quem.tenantId())
                : agentes.findByTenantIdAndUsuarioIdAndRevogadoFalseOrderByNome(quem.tenantId(), quem.id());
        Map<Long, String> nomes = usuarios.findAllById(lista.stream().map(Agente::getUsuarioId).collect(Collectors.toSet())).stream().collect(Collectors.toMap(Usuario::getId, Usuario::getNome));
        Instant desde = Instant.now().minus(certificados.janelaDeConexao());
        return lista.stream().map(a -> new AgenteDto(a.getId(), a.getNome(), a.getVersao(), a.getUsuarioId(), nomes.get(a.getUsuarioId()), a.getUltimoContato(),
                a.getUltimoContato() != null && a.getUltimoContato().isAfter(desde), ligacoes.findByAgenteId(a.getId()).size())).toList();
    }

    @Transactional
    public void revogar(UsuarioLogado quem, Long id) {
        Agente a = agentes.findByIdAndTenantId(id, quem.tenantId()).filter(x -> !x.isRevogado()).orElseThrow(() -> new NaoEncontradoException("Agente"));
        boolean gestao = quem.papel() == Papel.ADMIN || quem.papel() == Papel.GESTOR;
        if (!gestao && !a.getUsuarioId().equals(quem.id())) throw new NaoEncontradoException("Agente");   // não revela o de outra pessoa
        a.setRevogado(true);
        agentes.save(a);
        certificados.sincronizar(a, List.of());   // solta os certificados deste computador; o que só estava nele passa a "ausente"
        auditoria.registrar(a.getTenantId(), quem.id(), "AGENTE_REVOGADO", "Agente de " + a.getNome() + " desligado");
    }

    // ---------- chamadas do agente (autenticadas pelo token dele) ----------

    @Transactional(readOnly = true)
    public Optional<Agente> autenticar(String token) {
        if (token == null || !token.startsWith("vagt_")) return Optional.empty();
        return agentes.findByTokenHashAndRevogadoFalse(hash(token))
                .filter(a -> usuarios.findByIdAndTenantId(a.getUsuarioId(), a.getTenantId()).filter(Usuario::isAtivo).isPresent());   // quem saiu da empresa leva o agente junto
    }

    /** Batimento do agente: atualiza o último contato, recebe a lista de certificados (quando mudou) e devolve o que há para fazer. */
    @Transactional
    public ContatoResposta contato(Agente agente, Contato c) {
        Agente a = agentes.findById(agente.getId()).orElseThrow();
        a.setUltimoContato(Instant.now());
        if (c != null && c.versao() != null) a.setVersao(corta(c.versao().trim(), 30));
        agentes.save(a);
        if (c != null && c.certificados() != null) certificados.sincronizar(a, c.certificados());
        Instant agora = Instant.now();
        List<Tarefa> fazer = new java.util.ArrayList<>();
        for (AgenteTarefa t : tarefas.pendentesDoAgente(a.getId(), PageRequest.of(0, 3))) {
            if (tarefas.reservar(t.getId(), a.getId(), agora) != 1) continue;   // outro agente chegou primeiro
            ClienteCertificado cert = certificados.porId(t.getCertificadoId());
            fazer.add(new Tarefa(t.getId(), t.getTipo(), t.getUrl(), t.getContentType(), t.getCorpo(), cert.getThumbprint()));
        }
        return new ContatoResposta(fazer);
    }

    @Transactional
    public void resultado(Agente agente, Long id, Resultado r) {
        AgenteTarefa t = tarefas.findById(id).filter(x -> agente.getId().equals(x.getAgenteId()) && AgenteTarefa.EM_EXECUCAO.equals(x.getStatus())).orElseThrow(() -> new NaoEncontradoException("Tarefa"));
        if (r.erro() != null && !r.erro().isBlank()) {
            t.setStatus(AgenteTarefa.ERRO);
            t.setErro(corta(r.erro(), 500));
        } else {
            if (r.corpo() != null && r.corpo().length() > MAXIMO_RESPOSTA * 4 / 3 + 16) throw new NegocioException("Resposta grande demais");
            t.setStatus(AgenteTarefa.CONCLUIDA);
            t.setHttpStatus(r.httpStatus());
            t.setResposta(r.corpo());
        }
        t.setCorpo(null);
        tarefas.save(t);
    }

    // ---------- pedidos do Viriato ao agente ----------

    /**
     * Faz uma chamada HTTPS com o certificado de uma empresa, no computador onde ele está, e devolve o corpo da resposta.
     * Espera o agente buscar o trabalho (ele consulta a cada poucos segundos).
     */
    public byte[] executarHttps(Long tenantId, Long certificadoId, String url, String contentType, byte[] corpo) throws IOException {
        AgenteTarefa t = new AgenteTarefa();
        t.setTenantId(tenantId);
        t.setCertificadoId(certificadoId);
        t.setTipo("HTTPS_MTLS");
        t.setUrl(url);
        t.setContentType(contentType);
        t.setCorpo(Base64.getEncoder().encodeToString(corpo));
        t.setStatus(AgenteTarefa.PENDENTE);
        t = tarefas.save(t);
        long fim = System.nanoTime() + Duration.ofSeconds(esperaSegundos).toNanos();
        try {
            while (System.nanoTime() < fim) {
                Thread.sleep(250);
                AgenteTarefa atual = tarefas.findById(t.getId()).orElseThrow();
                if (AgenteTarefa.ERRO.equals(atual.getStatus())) throw new IOException(atual.getErro() == null ? "O agente não conseguiu fazer a chamada" : atual.getErro());
                if (AgenteTarefa.CONCLUIDA.equals(atual.getStatus())) {
                    byte[] resposta = atual.getResposta() == null ? new byte[0] : Base64.getDecoder().decode(atual.getResposta());
                    atual.setResposta(null);   // não fica guardada: o que importa vira documento no GED
                    tarefas.save(atual);
                    if (atual.getHttpStatus() == null || atual.getHttpStatus() / 100 != 2) throw new IOException("O servidor respondeu HTTP " + atual.getHttpStatus());
                    return resposta;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Consulta interrompida", e);
        }
        tarefas.expirar(t.getId());
        throw new IOException("O agente não respondeu a tempo. Confira se ele está aberto no computador do certificado");
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 600_000)
    @Transactional
    public void limpar() {
        int a = tarefas.apagarAnteriores(Instant.now().minus(Duration.ofDays(2)));
        int b = pareamentos.apagarAnteriores(Instant.now().minus(Duration.ofDays(1)));
        if (a + b > 0) log.info("Limpeza do agente: {} tarefas e {} códigos antigos", a, b);
    }

    private static String normalizar(String codigo) { return codigo.toUpperCase().replaceAll("[^A-Z0-9]", ""); }

    static String hash(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String corta(String s, int max) { return s.length() > max ? s.substring(0, max) : s; }
}
