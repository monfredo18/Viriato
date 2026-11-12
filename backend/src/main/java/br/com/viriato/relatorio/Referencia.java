package br.com.viriato.relatorio;

/** Qual data o filtro de período usa. */
public enum Referencia {
    ABERTURA,      // quando o processo foi aberto
    ENCERRAMENTO   // quando foi concluído ou cancelado (só entram processos encerrados)
}
