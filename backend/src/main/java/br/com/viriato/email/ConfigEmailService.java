package br.com.viriato.email;

import br.com.viriato.automacao.UrlWebhook;
import br.com.viriato.comum.NegocioException;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Servidor de e-mail (SMTP) de cada empresa, configurado pelo administrador. Sem configuração, vale o servidor padrão
 * do sistema (variáveis MAIL_*). A senha é guardada criptografada e nunca volta pela API.
 */
@Service
public class ConfigEmailService {
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9]([A-Za-z0-9.-]{0,253}[A-Za-z0-9])?");
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");

    /** O que a tela mostra. {@code padrao}: o servidor que vale quando a empresa não configurou o dela. */
    public record ConfigDto(boolean configurado, String host, Integer porta, SegurancaEmail seguranca, String usuario, boolean temSenha,
                            String remetente, String padrao, boolean padraoSoCaptura) {}

    public record SalvarReq(String host, Integer porta, SegurancaEmail seguranca, String usuario, String senha, String remetente) {}

    /** Configuração já pronta para uso (senha decifrada). {@code senhaIlegivel}: havia senha, mas não foi possível decifrá-la. */
    public record Smtp(String host, int porta, SegurancaEmail seguranca, String usuario, String senha, String remetente, boolean senhaIlegivel) {}

    private final ConfigEmailRepository repo;
    private final Cifra cifra;
    private final JavaMailSender padrao;
    private final boolean permitirRedeInterna;

    public ConfigEmailService(ConfigEmailRepository repo, Cifra cifra, JavaMailSender padrao,
                              @Value("${viriato.automacao.permitir-rede-interna:false}") boolean permitirRedeInterna) {
        this.repo = repo;
        this.cifra = cifra;
        this.padrao = padrao;
        this.permitirRedeInterna = permitirRedeInterna;
    }

    @Transactional(readOnly = true)
    public Optional<Smtp> doTenant(Long tenantId) {
        return repo.findById(tenantId).map(c -> {
            String senha = c.getSenhaCifrada() == null ? null : cifra.decifrar(c.getSenhaCifrada());
            return new Smtp(c.getHost(), c.getPorta(), c.getSeguranca(), c.getUsuario(), senha, c.getRemetente(),
                    c.getSenhaCifrada() != null && senha == null);
        });
    }

    @Transactional(readOnly = true)
    public ConfigDto obter(Long tenantId) {
        String servidorPadrao = padrao instanceof JavaMailSenderImpl j ? j.getHost() + ":" + j.getPort() : "desconhecido";
        boolean captura = padrao instanceof JavaMailSenderImpl j && soCaptura(j.getHost());
        return repo.findById(tenantId)
                .map(c -> new ConfigDto(true, c.getHost(), c.getPorta(), c.getSeguranca(), c.getUsuario(), c.getSenhaCifrada() != null,
                        c.getRemetente(), servidorPadrao, captura))
                .orElse(new ConfigDto(false, null, null, SegurancaEmail.STARTTLS, null, false, null, servidorPadrao, captura));
    }

    /** Servidores de desenvolvimento que só guardam os e-mails e nunca entregam em caixa de entrada. */
    public static boolean soCaptura(String host) {
        String h = host == null ? "" : host.toLowerCase();
        return h.equals("mailpit") || h.equals("mailhog") || h.equals("localhost") || h.equals("127.0.0.1") || h.equals("::1");
    }

    /** Senha: {@code null} mantém a atual, vazio apaga. */
    @Transactional
    public ConfigDto salvar(Long tenantId, SalvarReq req) {
        String host = req.host() == null ? "" : req.host().trim();
        if (!HOST.matcher(host).matches()) throw new NegocioException("Informe o endereço do servidor, como smtp.seudominio.com");
        if (req.porta() == null || req.porta() < 1 || req.porta() > 65535) throw new NegocioException("A porta deve estar entre 1 e 65535");
        if (req.seguranca() == null) throw new NegocioException("Escolha o tipo de segurança");
        String remetente = req.remetente() == null ? "" : req.remetente().trim();
        if (!EMAIL.matcher(remetente).matches() || remetente.length() > 190) throw new NegocioException("Informe um e-mail válido para o remetente");
        UrlWebhook.exigirHostPublico(host, permitirRedeInterna);

        ConfigEmail c = repo.findById(tenantId).orElseGet(ConfigEmail::new);
        String usuario = req.usuario() == null || req.usuario().isBlank() ? null : req.usuario().trim();
        if (usuario != null && usuario.length() > 255) throw new NegocioException("O usuário deve ter até 255 caracteres");
        String senhaCifrada = c.getSenhaCifrada();
        if (req.senha() != null) {
            if (req.senha().length() > 300) throw new NegocioException("A senha deve ter até 300 caracteres");
            senhaCifrada = req.senha().isEmpty() ? null : cifra.cifrar(req.senha());
        }
        if (usuario == null) senhaCifrada = null;                       // sem usuário não há autenticação
        else if (senhaCifrada == null) throw new NegocioException("Informe a senha do servidor de e-mail");

        c.setTenantId(tenantId);
        c.setHost(host);
        c.setPorta(req.porta());
        c.setSeguranca(req.seguranca());
        c.setUsuario(usuario);
        c.setSenhaCifrada(senhaCifrada);
        c.setRemetente(remetente);
        repo.save(c);
        return obter(tenantId);
    }

    /** Volta a usar o servidor padrão do sistema. */
    @Transactional
    public void remover(Long tenantId) { repo.deleteById(tenantId); }

    public boolean permitirRedeInterna() { return permitirRedeInterna; }

    /** Monta o remetente de e-mails com os dados da empresa (timeouts curtos, certificado conferido quando há criptografia). */
    public JavaMailSenderImpl construir(Smtp s) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(s.host());
        sender.setPort(s.porta());
        sender.setDefaultEncoding("UTF-8");
        Properties p = sender.getJavaMailProperties();
        p.put("mail.smtp.connectiontimeout", "10000");
        p.put("mail.smtp.timeout", "15000");
        p.put("mail.smtp.writetimeout", "15000");
        if (s.usuario() != null) {
            sender.setUsername(s.usuario());
            sender.setPassword(s.senha());
            p.put("mail.smtp.auth", "true");
        }
        switch (s.seguranca()) {
            case STARTTLS -> {
                p.put("mail.smtp.starttls.enable", "true");
                p.put("mail.smtp.starttls.required", "true");   // nunca cai para texto puro
                p.put("mail.smtp.ssl.checkserveridentity", "true");
            }
            case SSL -> {
                p.put("mail.smtp.ssl.enable", "true");
                p.put("mail.smtp.ssl.checkserveridentity", "true");
            }
            default -> { }
        }
        return sender;
    }
}
