package br.com.viriato.seguranca;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Limite por endereço de origem (janela deslizante) nas rotas públicas que recebem dados ({@code POST /api/auth/**},
 * como o login). Complementa o bloqueio por conta: quem tenta senhas em muitas contas, ou em uma conta que ainda não
 * bloqueou, a partir do mesmo endereço, é contido com HTTP 429. A contagem fica em memória (reinicia com o servidor;
 * com várias instâncias, cada uma conta a sua).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class LimiteDeTentativas extends OncePerRequestFilter {
    private final IpCliente ips;
    private final int maximo;
    private final long janelaMs;
    private final Map<String, Deque<Long>> acessos = new ConcurrentHashMap<>();
    private volatile long ultimaLimpeza = System.currentTimeMillis();

    public LimiteDeTentativas(IpCliente ips,
                              @Value("${viriato.seguranca.limite-ip.maximo:20}") int maximo,
                              @Value("${viriato.seguranca.limite-ip.janela-segundos:60}") long janelaSegundos) {
        this.ips = ips;
        this.maximo = maximo;
        this.janelaMs = janelaSegundos * 1000;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        return !(req.getRequestURI().startsWith("/api/auth/") && SessaoCookie.metodoAltera(req.getMethod()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        long espera = tentar(ips.de(req), System.currentTimeMillis());
        if (espera > 0) {
            res.setStatus(429);
            res.setHeader("Retry-After", String.valueOf(Math.max(1, (espera + 999) / 1000)));
            res.setContentType("application/json;charset=UTF-8");
            res.getOutputStream().write("{\"erro\":\"Muitas tentativas deste endereço. Aguarde um instante e tente de novo.\",\"codigo\":\"LIMITE\"}"
                    .getBytes(StandardCharsets.UTF_8));
            return;
        }
        chain.doFilter(req, res);
    }

    /** 0 = pode seguir; senão, quantos milissegundos faltam para liberar. */
    long tentar(String ip, long agora) {
        limparSeNecessario(agora);
        Deque<Long> janela = acessos.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (janela) {
            while (!janela.isEmpty() && agora - janela.peekFirst() >= janelaMs) janela.pollFirst();
            if (janela.size() >= maximo) return janelaMs - (agora - janela.peekFirst());
            janela.addLast(agora);
            return 0;
        }
    }

    private void limparSeNecessario(long agora) {
        if (agora - ultimaLimpeza < janelaMs) return;
        ultimaLimpeza = agora;
        for (Iterator<Map.Entry<String, Deque<Long>>> it = acessos.entrySet().iterator(); it.hasNext(); ) {
            Deque<Long> d = it.next().getValue();
            synchronized (d) { if (d.isEmpty() || agora - d.peekLast() >= janelaMs) it.remove(); }
        }
    }
}
