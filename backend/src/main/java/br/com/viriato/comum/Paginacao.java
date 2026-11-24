package br.com.viriato.comum;

import java.util.List;
import org.springframework.http.ResponseEntity;

/**
 * Paginação das listas que as telas usam também como fonte de seleções (equipe, fluxos, notificações). O corpo continua
 * sendo a lista; o total vai nos cabeçalhos {@code X-Total-Count}, {@code X-Page}, {@code X-Page-Size} e {@code X-Total-Pages}.
 * Sem parâmetros vale a primeira página com o tamanho padrão; o tamanho nunca passa do máximo.
 */
public final class Paginacao {
    private Paginacao() {}

    public static int tamanho(Integer pedido, int padrao, int maximo) {
        return pedido == null || pedido < 1 ? padrao : Math.min(pedido, maximo);
    }

    public static int pagina(Integer pedida) { return pedida == null || pedida < 0 ? 0 : pedida; }

    /** Recorta uma lista já filtrada e ordenada. */
    public static <T> ResponseEntity<List<T>> recortar(List<T> todos, Integer pagina, Integer tamanho, int padrao, int maximo) {
        int t = tamanho(tamanho, padrao, maximo), p = pagina(pagina);
        int de = (int) Math.min((long) p * t, todos.size());
        int ate = Math.min(de + t, todos.size());
        return resposta(todos.subList(de, ate), todos.size(), p, t);
    }

    /** Quando o banco já devolveu só a página pedida. */
    public static <T> ResponseEntity<List<T>> resposta(List<T> pagina, long total, int numero, int tamanho) {
        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(total))
                .header("X-Page", String.valueOf(numero))
                .header("X-Page-Size", String.valueOf(tamanho))
                .header("X-Total-Pages", String.valueOf(total == 0 ? 0 : (total + tamanho - 1) / tamanho))
                .body(pagina);
    }
}
