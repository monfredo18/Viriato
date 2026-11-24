package br.com.viriato.ged;

import br.com.viriato.crm.CertificadoService.CertificadoEmUso;
import java.io.IOException;
import java.util.List;

/**
 * Consulta ao serviço nacional de Distribuição de DF-e (NFeDistribuicaoDFe) da SEFAZ, com o certificado A1 da empresa
 * atendida. Quem chama já abriu o certificado pelo cofre (e registrou o uso na auditoria).
 */
public interface SefazDfeConnector {
    /** Um documento devolvido pela SEFAZ: o NSU, o esquema (ex.: procNFe_v4.00.xsd) e o XML já descompactado. */
    record DocumentoDfe(String nsu, String esquema, byte[] xml) {}

    /** {@code codigoStatus}: 138 = há documentos; 137 = nenhum documento (esperar 1 hora); 656 = consumo indevido (esperar 1 hora). */
    record Resposta(String codigoStatus, String motivo, String ultimoNsu, String maxNsu, List<DocumentoDfe> documentos) {}

    boolean habilitado();

    Resposta consultar(CertificadoEmUso certificado, String cnpj, String uf, String ultimoNsu) throws IOException;
}
