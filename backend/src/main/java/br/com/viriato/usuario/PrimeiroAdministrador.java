package br.com.viriato.usuario;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Sem cadastro aberto de empresas, a primeira conta de administrador nasce da configuração: se o sistema
 * está vazio e ADMIN_NOME e ADMIN_SENHA foram definidos, cria a empresa e o administrador. A senha é
 * provisória, então ele a troca ao entrar pela primeira vez.
 */
@Component
public class PrimeiroAdministrador implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(PrimeiroAdministrador.class);

    private final UsuarioRepository usuarios;
    private final AuthService auth;
    private final String nome;
    private final String senha;
    private final String empresa;

    public PrimeiroAdministrador(UsuarioRepository usuarios, AuthService auth,
                                 @Value("${viriato.admin-inicial.nome:}") String nome,
                                 @Value("${viriato.admin-inicial.senha:}") String senha,
                                 @Value("${viriato.admin-inicial.empresa:Minha empresa}") String empresa) {
        this.usuarios = usuarios;
        this.auth = auth;
        this.nome = nome;
        this.senha = senha;
        this.empresa = empresa;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (usuarios.count() > 0 || nome.isBlank() || senha.isBlank()) return;
        if (senha.length() < 8 || senha.length() > 72) {
            log.warn("ADMIN_SENHA precisa ter de 8 a 72 caracteres; o administrador inicial não foi criado.");
            return;
        }
        auth.criarEmpresaComAdmin(empresa, nome, null, senha, true);
        log.info("Administrador inicial \"{}\" criado; a senha deve ser trocada no primeiro acesso.", nome);
    }
}
