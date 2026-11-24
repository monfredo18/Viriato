package br.com.viriato.usuario;

import br.com.viriato.auditoria.AuditoriaService;
import br.com.viriato.cadastro.CadastroService;
import br.com.viriato.comum.CodigoNecessarioException;
import br.com.viriato.comum.ContaBloqueadaException;
import br.com.viriato.comum.NegocioException;
import br.com.viriato.fluxo.FluxoService;
import br.com.viriato.seguranca.Cifra;
import br.com.viriato.seguranca.JwtService;
import br.com.viriato.seguranca.SessaoRevogada;
import br.com.viriato.seguranca.SessaoRevogadaRepository;
import br.com.viriato.seguranca.Totp;
import br.com.viriato.seguranca.UsuarioLogado;
import br.com.viriato.tenant.Tenant;
import br.com.viriato.tenant.TenantRepository;
import java.util.List;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private final UsuarioRepository usuarios;
    private final TenantRepository tenants;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final FluxoService fluxos;
    private final CadastroService cadastros;
    private final ContaSeguranca contas;
    private final AuditoriaService auditoria;
    private final Cifra cifra;
    private final SessaoRevogadaRepository revogadas;
    private final boolean exigirDoisFatoresAdmin;
    private final String hashFalso;   // gasta o mesmo tempo quando o nome não existe

    public AuthService(UsuarioRepository usuarios, TenantRepository tenants, PasswordEncoder encoder,
                       JwtService jwt, FluxoService fluxos, CadastroService cadastros, ContaSeguranca contas,
                       AuditoriaService auditoria, Cifra cifra, SessaoRevogadaRepository revogadas,
                       @org.springframework.beans.factory.annotation.Value("${viriato.seguranca.exigir-2fa-admin:false}") boolean exigirDoisFatoresAdmin) {
        this.exigirDoisFatoresAdmin = exigirDoisFatoresAdmin;
        this.usuarios = usuarios;
        this.tenants = tenants;
        this.encoder = encoder;
        this.jwt = jwt;
        this.fluxos = fluxos;
        this.cadastros = cadastros;
        this.contas = contas;
        this.auditoria = auditoria;
        this.cifra = cifra;
        this.revogadas = revogadas;
        this.hashFalso = encoder.encode("nao-existe");
    }

    public record Sessao(String token, UsuarioDto usuario, String empresa) {
        /** Sem o token no corpo: ele vai só no cookie HttpOnly. */
        public Sessao semToken() { return new Sessao(null, usuario, empresa); }
    }

    private Sessao nova(Usuario u, String empresa) {
        return new Sessao(jwt.gerar(u), UsuarioDto.de(u).comExigenciaDeDoisFatores(exigirDoisFatoresAdmin), empresa);
    }

    /**
     * Entra com nome (ou, em contas antigas, o e-mail) e senha. O nome não diferencia maiúsculas de
     * minúsculas. Se mais de uma conta ativa tiver o mesmo nome E a mesma senha, o acesso é recusado
     * (o administrador deve renomear uma delas). Senha (ou código) errado soma um erro; passando de
     * {@value ContaSeguranca#ERROS_PERMITIDOS} erros a conta bloqueia e só o suporte desbloqueia.
     */
    @Transactional
    public Sessao login(String identificador, String senha, String codigo, String ip, String userAgent) {
        String id = identificador.trim();
        List<Usuario> candidatos = id.contains("@")
                ? usuarios.findByEmailIgnoreCase(id).map(List::of).orElse(List.of())
                : usuarios.findByNomeIgnoreCase(id);
        List<Usuario> ativos = candidatos.stream()
                .filter(Usuario::isAtivo)
                .filter(u -> tenants.findById(u.getTenantId()).map(Tenant::isAtivo).orElse(false))
                .toList();
        if (ativos.isEmpty()) {
            encoder.matches(senha, hashFalso);   // gasta o mesmo tempo quando o nome não existe
            throw new BadCredentialsException("credenciais");
        }
        List<Usuario> certos = ativos.stream().filter(u -> encoder.matches(senha, u.getSenhaHash())).toList();

        if (certos.size() == 1) {
            Usuario u = certos.get(0);
            if (u.isBloqueado()) {
                auditoria.registrar(u.getTenantId(), u.getId(), "LOGIN_RECUSADO", "Conta bloqueada", ip, userAgent);
                throw new ContaBloqueadaException();
            }
            if (u.isTotpAtivo()) {
                if (codigo == null || codigo.isBlank()) throw new CodigoNecessarioException();
                long passo = Totp.verificar(cifra.abrir(u.getTotpSegredo()), codigo, u.getTotpUltimoPasso());
                if (passo < 0) {
                    boolean bloqueou = contas.registrarFalha(u.getId());
                    auditoria.registrar(u.getTenantId(), u.getId(), "LOGIN_FALHA", "Código de verificação inválido", ip, userAgent);
                    if (bloqueou) throw new ContaBloqueadaException();
                    throw new NegocioException("Código de verificação inválido");
                }
                u.setTotpUltimoPasso(passo);
            }
            u.setTentativasFalhas(0);
            auditoria.registrar(u.getTenantId(), u.getId(), "LOGIN", null, ip, userAgent);
            String empresa = tenants.findById(u.getTenantId()).orElseThrow().getNome();
            return nova(u, empresa);
        }

        // Não entrou: cada conta ativa com esse nome ganha um erro (as que já estavam bloqueadas continuam bloqueadas).
        boolean bloqueada = false;
        for (Usuario u : ativos) {
            if (!certos.contains(u)) {
                bloqueada |= contas.registrarFalha(u.getId());
                auditoria.registrar(u.getTenantId(), u.getId(), "LOGIN_FALHA", "Senha inválida", ip, userAgent);
            }
        }
        if (bloqueada) throw new ContaBloqueadaException();
        throw new BadCredentialsException("credenciais");
    }

    /** O token deixa de valer agora, mesmo antes de expirar. */
    @Transactional
    public void sair(String token) {
        jwt.validar(token).ifPresent(t -> {
            if (t.jti() == null || t.expira() == null || revogadas.existsById(t.jti())) return;
            SessaoRevogada r = new SessaoRevogada();
            r.setJti(t.jti());
            r.setExpiraEm(t.expira());
            revogadas.save(r);
        });
    }

    /** Troca a própria senha (quem não está com senha provisória). Encerra as outras sessões e devolve uma sessão nova. */
    @Transactional
    public Sessao alterarSenha(UsuarioLogado quem, String senhaAtual, String novaSenha) {
        if (quem.papel() == br.com.viriato.usuario.Papel.USUARIO) {
            throw new org.springframework.security.access.AccessDeniedException("Quem tem papel Usuário não troca a própria senha: peça ao administrador");
        }
        Usuario u = usuarios.findByIdAndTenantId(quem.id(), quem.tenantId()).orElseThrow();
        if (u.isBloqueado()) throw new ContaBloqueadaException();
        if (!encoder.matches(senhaAtual, u.getSenhaHash())) {
            if (contas.registrarFalha(u.getId())) throw new ContaBloqueadaException();
            throw new NegocioException("A senha atual está incorreta");
        }
        if (encoder.matches(novaSenha, u.getSenhaHash())) throw new NegocioException("A nova senha precisa ser diferente da atual");
        u.setSenhaHash(encoder.encode(novaSenha));
        u.setTentativasFalhas(0);
        u.setVersaoSessao(u.getVersaoSessao() + 1);   // as demais sessões (e o token usado agora) deixam de valer
        usuarios.save(u);
        auditoria.registrar(u.getTenantId(), u.getId(), "SENHA_ALTERADA", "A própria pessoa trocou a senha");
        String empresa = tenants.findById(u.getTenantId()).orElseThrow().getNome();
        return nova(u, empresa);
    }

    // ---------- verificação em duas etapas ----------

    public record ConfiguracaoDoisFatores(String segredo, String uri) {}

    /** Gera o segredo e o devolve para ser cadastrado no aplicativo; só passa a valer depois de {@link #ativarDoisFatores}. */
    @Transactional
    public ConfiguracaoDoisFatores iniciarDoisFatores(UsuarioLogado quem) {
        Usuario u = usuarios.findByIdAndTenantId(quem.id(), quem.tenantId()).orElseThrow();
        if (u.isTotpAtivo()) throw new NegocioException("A verificação em duas etapas já está ativa");
        String segredo = Totp.novoSegredo();
        u.setTotpSegredo(cifra.cifrar(segredo));
        usuarios.save(u);
        String conta = java.net.URLEncoder.encode(u.getNome(), java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
        return new ConfiguracaoDoisFatores(segredo, "otpauth://totp/Viriato:" + conta + "?secret=" + segredo + "&issuer=Viriato");
    }

    @Transactional
    public UsuarioDto ativarDoisFatores(UsuarioLogado quem, String codigo) {
        Usuario u = usuarios.findByIdAndTenantId(quem.id(), quem.tenantId()).orElseThrow();
        if (u.isTotpAtivo()) throw new NegocioException("A verificação em duas etapas já está ativa");
        String segredo = u.getTotpSegredo() == null ? null : cifra.abrir(u.getTotpSegredo());
        if (segredo == null) throw new NegocioException("Comece pela configuração: gere o segredo antes de confirmar o código");
        long passo = Totp.verificar(segredo, codigo, null);
        if (passo < 0) throw new NegocioException("Código inválido. Confira o aplicativo e tente de novo");
        u.setTotpAtivo(true);
        u.setTotpUltimoPasso(passo);
        auditoria.registrar(u.getTenantId(), u.getId(), "2FA_ATIVADA", null);
        return UsuarioDto.de(usuarios.save(u));
    }

    /** A própria pessoa desliga confirmando a senha. */
    @Transactional
    public UsuarioDto desativarDoisFatores(UsuarioLogado quem, String senha) {
        Usuario u = usuarios.findByIdAndTenantId(quem.id(), quem.tenantId()).orElseThrow();
        if (!encoder.matches(senha, u.getSenhaHash())) {
            if (contas.registrarFalha(u.getId())) throw new ContaBloqueadaException();
            throw new NegocioException("A senha está incorreta");
        }
        limparDoisFatores(u);
        auditoria.registrar(u.getTenantId(), u.getId(), "2FA_DESATIVADA", null);
        return UsuarioDto.de(usuarios.save(u));
    }

    public static void limparDoisFatores(Usuario u) {
        u.setTotpAtivo(false);
        u.setTotpSegredo(null);
        u.setTotpUltimoPasso(null);
    }

    /** Cadastro aberto de empresa (desligado por padrão): cria a empresa, o primeiro ADMIN e um fluxo padrão. */
    @Transactional
    public Sessao registrarEmpresa(String empresa, String nome, String email, String senha) {
        if (email != null && usuarios.existsByEmailIgnoreCase(email)) throw new NegocioException("E-mail já cadastrado");
        Usuario u = criarEmpresaComAdmin(empresa, nome, email, senha, false);
        Tenant t = tenants.findById(u.getTenantId()).orElseThrow();
        return nova(u, t.getNome());
    }

    /** Cria a empresa, o administrador e os padrões. {@code provisoria}: o administrador troca a senha no primeiro acesso. */
    @Transactional
    public Usuario criarEmpresaComAdmin(String empresa, String nome, String email, String senha, boolean provisoria) {
        Tenant t = new Tenant();
        t.setNome(empresa.trim());
        t = tenants.save(t);

        Usuario u = new Usuario();
        u.setTenantId(t.getId());
        u.setNome(nome.trim());
        u.setEmail(email == null || email.isBlank() ? null : email.trim().toLowerCase());
        u.setSenhaHash(encoder.encode(senha));
        u.setPapel(Papel.ADMIN);
        u.setSenhaProvisoria(provisoria);
        u = usuarios.save(u);

        var fluxoPadrao = fluxos.criarFluxoPadrao(t.getId());
        cadastros.criarPadroes(t.getId(), fluxoPadrao.id());
        return u;
    }

    /** Primeiro acesso (ou após zerar): a pessoa troca a senha provisória pela própria, que não pode ser a mesma. */
    @Transactional
    public UsuarioDto definirSenhaPropria(UsuarioLogado quem, String novaSenha) {
        Usuario u = usuarios.findByIdAndTenantId(quem.id(), quem.tenantId()).orElseThrow();
        if (!u.isSenhaProvisoria()) throw new NegocioException("Você já tem uma senha própria");
        if (encoder.matches(novaSenha, u.getSenhaHash())) {
            throw new NegocioException("A nova senha precisa ser diferente da senha inicial");
        }
        u.setSenhaHash(encoder.encode(novaSenha));
        u.setSenhaProvisoria(false);
        return UsuarioDto.de(usuarios.save(u));
    }
}
