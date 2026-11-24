package br.com.viriato.seguranca;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * Endereço de origem de quem fez a requisição. Com {@code server.forward-headers-strategy=native}, o Tomcat só aceita o
 * {@code X-Forwarded-For} vindo de proxies internos (o nginx do projeto) e já troca o endereço da conexão pelo do cliente
 * real; um cabeçalho forjado por quem acessa direto é ignorado. Por isso basta ler o endereço da requisição.
 */
@Component
public class IpCliente {
    public String de(HttpServletRequest req) { return req.getRemoteAddr(); }
}
