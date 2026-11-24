package br.com.viriato.ged;

import br.com.viriato.comum.NegocioException;
import br.com.viriato.crm.CertificadoService.CertificadoEmUso;

/** Consulta à SEFAZ desligada (padrão). Ligue com {@code VIRIATO_SEFAZ_HABILITADO=true}. */
public class NoOpSefazConnector implements SefazDfeConnector {
    @Override public boolean habilitado() { return false; }

    @Override
    public Resposta consultar(CertificadoEmUso certificado, String cnpj, String uf, String ultimoNsu) {
        throw new NegocioException("A consulta automática à SEFAZ está desligada neste ambiente (VIRIATO_SEFAZ_HABILITADO)");
    }
}
