package br.com.viriato.notificacao;

import br.com.viriato.automacao.UrlWebhook;
import br.com.viriato.comum.NegocioException;
import br.com.viriato.email.ConfigEmailService;
import br.com.viriato.email.ConfigEmailService.Smtp;
import jakarta.mail.AuthenticationFailedException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import javax.net.ssl.SSLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Envio de e-mail. Cada empresa pode ter o próprio servidor SMTP (configurado na tela pelo administrador); sem ele, vale o
 * servidor padrão do sistema. Falha de e-mail nunca derruba a operação de negócio.
 */
@Service
public class EmailService {
    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final JavaMailSender padrao;
    private final ConfigEmailService configs;
    private final boolean habilitado;
    private final String remetentePadrao;

    public EmailService(JavaMailSender padrao, ConfigEmailService configs,
                        @Value("${viriato.mail.habilitado}") boolean habilitado,
                        @Value("${viriato.mail.remetente}") String remetentePadrao) {
        this.padrao = padrao;
        this.configs = configs;
        this.habilitado = habilitado;
        this.remetentePadrao = remetentePadrao;
    }

    /** Há como enviar: a empresa configurou o servidor dela, ou o envio do servidor padrão está ligado. */
    public boolean habilitado(Long tenantId) {
        return configs.doTenant(tenantId).isPresent() || habilitado;
    }

    /** Descrição do servidor que será usado, para mensagens ao administrador. */
    public String servidorEmUso(Long tenantId) {
        return configs.doTenant(tenantId).map(s -> s.host() + ":" + s.porta())
                .orElseGet(() -> padrao instanceof JavaMailSenderImpl j ? j.getHost() + ":" + j.getPort() : "servidor padrão");
    }

    /**
     * Envio imediato, com anexo opcional, para quem precisa saber se deu certo (automações e teste).
     *
     * @return {@code null} se enviou; senão o motivo da falha, já em linguagem comum
     */
    public String enviarAgora(Long tenantId, String para, String assunto, String texto, String nomeAnexo, byte[] anexo) {
        JavaMailSender sender = padrao;
        String de = remetentePadrao;
        String servidor = padrao instanceof JavaMailSenderImpl j ? j.getHost() + ":" + j.getPort() : "o servidor padrão";
        try {
            Smtp cfg = configs.doTenant(tenantId).orElse(null);
            if (cfg != null) {
                if (cfg.senhaIlegivel()) return "A senha do servidor de e-mail não pôde ser lida. Salve a configuração de e-mail novamente.";
                try {
                    UrlWebhook.exigirHostPublico(cfg.host(), configs.permitirRedeInterna());
                } catch (NegocioException e) {
                    return e.getMessage();
                }
                sender = configs.construir(cfg);
                de = cfg.remetente();
                servidor = cfg.host() + ":" + cfg.porta();
            } else if (!habilitado) {
                return "O envio de e-mail está desligado neste ambiente";
            }
            var msg = sender.createMimeMessage();
            var h = new MimeMessageHelper(msg, anexo != null, "UTF-8");
            h.setFrom(de);
            h.setTo(para);
            h.setSubject(assunto);
            h.setText(texto);
            if (anexo != null) h.addAttachment(nomeAnexo, new ByteArrayResource(anexo), "application/pdf");
            sender.send(msg);
            return null;
        } catch (Exception e) {
            log.warn("Falha ao enviar e-mail para {}: {}", para, e.getMessage());
            return explicar(e, servidor);
        }
    }

    /** Aviso por e-mail em segundo plano (o sininho já é o aviso principal). */
    @Async
    public void enviar(Long tenantId, String para, String assunto, String texto) {
        if (!habilitado(tenantId)) return;
        String erro = enviarAgora(tenantId, para, assunto, texto, null, null);
        if (erro != null) log.warn("Aviso por e-mail para {} não enviado: {}", para, erro);
    }

    /** Transforma o erro técnico numa explicação que o administrador consiga agir sobre. */
    static String explicar(Throwable erro, String servidor) {
        for (Throwable t = erro; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof AuthenticationFailedException || t instanceof MailAuthenticationException) {
                return "O servidor " + servidor + " recusou o usuário ou a senha. No Gmail e no Microsoft 365 é preciso usar uma senha de aplicativo, não a senha da conta.";
            }
            if (t instanceof UnknownHostException) return "Não encontrei o servidor " + servidor + ". Confira o endereço.";
            if (t instanceof ConnectException) return "Não foi possível conectar a " + servidor + " (conexão recusada ou bloqueada). Confira o endereço e a porta.";
            if (t instanceof SocketTimeoutException) return "O servidor " + servidor + " demorou demais para responder. Confira o endereço e a porta.";
            if (t instanceof SSLException) {
                return "Falha na conexão segura com " + servidor + ". Confira a porta e o tipo de segurança (587 costuma ser STARTTLS; 465, SSL).";
            }
        }
        String msg = erro.getMessage() == null ? erro.getClass().getSimpleName() : erro.getMessage();
        return msg.length() > 300 ? msg.substring(0, 300) : msg;
    }
}
