package br.com.viriato.usuario;

import br.com.viriato.cadastro.CadastroService;
import br.com.viriato.comum.NegocioException;
import br.com.viriato.fluxo.FluxoService;
import br.com.viriato.seguranca.JwtService;
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
    private final String hashFalso;   // gasta o mesmo tempo quando o nome não existe

    public AuthService(UsuarioRepository usuarios, TenantRepository tenants, PasswordEncoder encoder,
                       JwtService jwt, FluxoService fluxos, CadastroService cadastros) {
        this.usuarios = usuarios;
        this.tenants = tenants;
        this.encoder = encoder;
        this.jwt = jwt;
        this.fluxos = fluxos;
        this.cadastros = cadastros;
        this.hashFalso = encoder.encode("nao-existe");
    }

    public record Sessao(String token, UsuarioDto usuario, String empresa) {}

    /**
     * Entra com nome (ou, em contas antigas, o e-mail) e senha. O nome não diferencia maiúsculas de
     * minúsculas. Se mais de uma conta ativa tiver o mesmo nome E a mesma senha, o acesso é recusado
     * (o administrador deve renomear uma delas).
     */
    @Transactional(readOnly = true)
    public Sessao login(String identificador, String senha) {
        String id = identificador.trim();
        List<Usuario> candidatos = id.contains("@")
                ? usuarios.findByEmailIgnoreCase(id).map(List::of).orElse(List.of())
                : usuarios.findByNomeIgnoreCase(id);
        if (candidatos.isEmpty()) encoder.matches(senha, hashFalso);
        List<Usuario> aceitos = candidatos.stream()
                .filter(Usuario::isAtivo)
                .filter(u -> tenants.findById(u.getTenantId()).map(Tenant::isAtivo).orElse(false))
                .filter(u -> encoder.matches(senha, u.getSenhaHash()))
                .toList();
        if (aceitos.size() != 1) throw new BadCredentialsException("credenciais");
        Usuario u = aceitos.get(0);
        String empresa = tenants.findById(u.getTenantId()).orElseThrow().getNome();
        return new Sessao(jwt.gerar(u), UsuarioDto.de(u), empresa);
    }

    /** Cadastro aberto de empresa (desligado por padrão): cria a empresa, o primeiro ADMIN e um fluxo padrão. */
    @Transactional
    public Sessao registrarEmpresa(String empresa, String nome, String email, String senha) {
        if (email != null && usuarios.existsByEmailIgnoreCase(email)) throw new NegocioException("E-mail já cadastrado");
        Usuario u = criarEmpresaComAdmin(empresa, nome, email, senha, false);
        Tenant t = tenants.findById(u.getTenantId()).orElseThrow();
        return new Sessao(jwt.gerar(u), UsuarioDto.de(u), t.getNome());
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
