package br.com.viriato.automacao;

import br.com.viriato.comum.NegocioException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * Proteção contra o sistema ser usado para alcançar a rede interna (SSRF): o webhook só vai para endereços públicos,
 * por HTTPS, sem credenciais na URL. {@code permitirRedeInterna} existe só para desenvolvimento e testes.
 * Na configuração a URL é conferida pelo texto; na hora de enviar, o nome é resolvido e TODOS os endereços conferidos.
 */
public final class UrlWebhook {
    static final int MAXIMO = 500;

    private UrlWebhook() {}

    /** Confere a URL pelo que está escrito (sem consultar DNS). */
    public static URI validar(String texto, boolean permitirRedeInterna) {
        if (texto == null || texto.isBlank()) throw new NegocioException("Informe o endereço (URL) do webhook");
        String t = texto.trim();
        if (t.length() > MAXIMO) throw new NegocioException("O endereço do webhook deve ter até " + MAXIMO + " caracteres");
        URI uri;
        try {
            uri = URI.create(t);
        } catch (IllegalArgumentException e) {
            throw new NegocioException("Endereço do webhook inválido");
        }
        String esquema = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!esquema.equals("https") && !(esquema.equals("http") && permitirRedeInterna)) {
            throw new NegocioException("O webhook precisa usar https://");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) throw new NegocioException("Endereço do webhook inválido");
        if (uri.getUserInfo() != null) throw new NegocioException("O endereço do webhook não pode ter usuário e senha");
        if (!permitirRedeInterna && pareceInterno(uri.getHost())) {
            throw new NegocioException("O webhook não pode apontar para um endereço da rede interna");
        }
        return uri;
    }

    private static boolean pareceInterno(String host) {
        String h = host.toLowerCase();
        if (h.equals("localhost") || h.endsWith(".localhost") || h.endsWith(".local") || h.endsWith(".internal")) return true;
        if (!h.matches("[0-9.]+") && !h.contains(":")) return false;   // nome comum: a resolução confere na hora de enviar
        try {
            return interno(InetAddress.getByName(h));   // IP escrito por extenso: não faz consulta DNS
        } catch (UnknownHostException e) {
            return true;
        }
    }

    /** Na hora de enviar: resolve o nome e recusa se QUALQUER endereço for interno. Devolve o endereço a usar. */
    public static void conferirDestino(URI uri, boolean permitirRedeInterna) throws UnknownHostException {
        if (permitirRedeInterna) return;
        for (InetAddress a : InetAddress.getAllByName(uri.getHost())) {
            if (interno(a)) throw new NegocioException("O endereço do webhook aponta para a rede interna e foi bloqueado");
        }
    }

    /**
     * Para servidores que o sistema acessa a pedido do administrador (como o SMTP): o nome é resolvido e recusado se
     * qualquer endereço for da rede interna. {@code permitirRedeInterna} existe só para desenvolvimento e testes.
     */
    public static void exigirHostPublico(String host, boolean permitirRedeInterna) {
        if (permitirRedeInterna) return;
        String h = host.toLowerCase();
        if (h.equals("localhost") || h.endsWith(".localhost") || h.endsWith(".local") || h.endsWith(".internal")) {
            throw new NegocioException("O servidor de e-mail não pode ser um endereço da rede interna");
        }
        try {
            for (InetAddress a : InetAddress.getAllByName(host)) {
                if (interno(a)) throw new NegocioException("O servidor de e-mail não pode ser um endereço da rede interna");
            }
        } catch (UnknownHostException e) {
            throw new NegocioException("Não encontrei o servidor \"" + host + "\". Confira o endereço.");
        }
    }

    static boolean interno(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress() || a.isMulticastAddress()) return true;
        byte[] b = a.getAddress();
        if (b.length == 4) {
            int p = b[0] & 0xFF, s = b[1] & 0xFF;
            return p == 0 || (p == 100 && s >= 64 && s <= 127);   // 0.0.0.0/8 e 100.64.0.0/10 (CGNAT)
        }
        return (b[0] & 0xFE) == 0xFC;   // fc00::/7 (endereços locais únicos IPv6)
    }
}
