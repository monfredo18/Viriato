package br.com.viriato.ged;

import java.io.IOException;
import java.util.List;

/**
 * Consulta ao serviço nacional de Distribuição de DF-e (NFeDistribuicaoDFe) da SEFAZ. O conector monta o pedido e lê a resposta;
 * a chamada HTTPS com o certificado da empresa (TLS mútuo) é feita no computador do usuário pelo agente, por meio do {@link Postador}.
 */
public interface SefazDfeConnector {
    /** Um documento devolvido pela SEFAZ: o NSU, o esquema (ex.: procNFe_v4.00.xsd) e o XML já descompactado. */
    record DocumentoDfe(String nsu, String esquema, byte[] xml) {}

    /** {@code codigoStatus}: 138 = há documentos; 137 = nenhum documento (esperar 1 hora); 656 = consumo indevido (esperar 1 hora). */
    record Resposta(String codigoStatus, String motivo, String ultimoNsu, String maxNsu, List<DocumentoDfe> documentos) {}

    /** Faz um POST HTTPS autenticado pelo certificado da empresa e devolve o corpo da resposta (já conferido o HTTP 2xx). */
    @FunctionalInterface
    interface Postador {
        byte[] postar(String url, String contentType, byte[] corpo) throws IOException;
    }

    boolean habilitado();

    Resposta consultar(Postador postador, String cnpj, String uf, String ultimoNsu) throws IOException;
}
