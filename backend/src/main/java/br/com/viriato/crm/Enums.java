package br.com.viriato.crm;

public final class Enums {
    private Enums() {}

    public enum RegimeTributario { MEI, SIMPLES_NACIONAL, LUCRO_PRESUMIDO, LUCRO_REAL, IMUNE_ISENTA }
    public enum StatusContratual { ONBOARDING, ATIVO, SUSPENSO, OFFBOARDING, ENCERRADO }
    public enum AreaContato { FISCAL, CONTABIL, DP, FINANCEIRO }
    public enum TipoCertificado { A1, A3 }
    public enum TipoProcuracao { ECAC, SEFAZ, PREFEITURA, FGTS_CONECTIVIDADE, OUTRO }
    public enum TipoChecklist { ONBOARDING, OFFBOARDING }
}
