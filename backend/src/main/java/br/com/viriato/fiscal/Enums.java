package br.com.viriato.fiscal;

public final class Enums {
    private Enums() {}

    public enum EscopoFeriado { NACIONAL, ESTADUAL, MUNICIPAL }
    /** MANTER_DATA: dias corridos, sem ajuste. ANTECIPAR: cai em fim de semana ou feriado, vai para o dia útil anterior. POSTERGAR: para o seguinte. */
    public enum RegraDiaUtil { MANTER_DATA, ANTECIPAR_DIA_UTIL, POSTERGAR_DIA_UTIL }
    public enum Periodicidade { MENSAL, TRIMESTRAL, ANUAL }
    /** DIA_DO_MES: um dia fixo; ENESIMO_DIA_UTIL: o 5º, 10º… dia útil; ULTIMO_DIA_UTIL: o último dia útil do mês. */
    public enum ModoVencimento { DIA_DO_MES, ENESIMO_DIA_UTIL, ULTIMO_DIA_UTIL }
    public enum Esfera { FEDERAL, ESTADUAL, MUNICIPAL }
}
