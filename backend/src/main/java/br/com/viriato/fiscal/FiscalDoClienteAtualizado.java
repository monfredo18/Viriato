package br.com.viriato.fiscal;

/** Publicado quando os dados fiscais de uma empresa atendida mudam (regime, UF, CNAE): as obrigações compatíveis são vinculadas. */
public record FiscalDoClienteAtualizado(Long tenantId, Long clienteId) {}
