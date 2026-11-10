package br.com.viriato.usuario;

import br.com.viriato.comum.NegocioException;
import br.com.viriato.fluxo.FluxoService;
import br.com.viriato.seguranca.JwtService;
import br.com.viriato.tenant.Tenant;
import br.com.viriato.tenant.TenantRepository;
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

    public AuthService(UsuarioRepository usuarios, TenantRepository tenants, PasswordEncoder encoder,
                       JwtService jwt, FluxoService fluxos) {
        this.usuarios = usuarios;
        this.tenants = tenants;
        this.encoder = encoder;
        this.jwt = jwt;
        this.fluxos = fluxos;
    }

    public record Sessao(String token, UsuarioDto usuario, String empresa) {}

    @Transactional(readOnly = true)
    public Sessao login(String email, String senha) {
        Usuario u = usuarios.findByEmailIgnoreCase(email.trim())
                .orElseThrow(() -> new BadCredentialsException("credenciais"));
        Tenant t = tenants.findById(u.getTenantId()).orElseThrow();
        if (!u.isAtivo() || !t.isAtivo() || !encoder.matches(senha, u.getSenhaHash())) {
            throw new BadCredentialsException("credenciais");
        }
        return new Sessao(jwt.gerar(u), UsuarioDto.de(u), t.getNome());
    }

    /** Onboarding self-service: cria a empresa, o primeiro ADMIN e um fluxo padrão. */
    @Transactional
    public Sessao registrarEmpresa(String empresa, String nome, String email, String senha) {
        if (usuarios.existsByEmailIgnoreCase(email)) throw new NegocioException("E-mail já cadastrado");
        Tenant t = new Tenant();
        t.setNome(empresa.trim());
        t = tenants.save(t);

        Usuario u = new Usuario();
        u.setTenantId(t.getId());
        u.setNome(nome.trim());
        u.setEmail(email.trim().toLowerCase());
        u.setSenhaHash(encoder.encode(senha));
        u.setPapel(Papel.ADMIN);
        u = usuarios.save(u);

        fluxos.criarFluxoPadrao(t.getId());
        return new Sessao(jwt.gerar(u), UsuarioDto.de(u), t.getNome());
    }
}
