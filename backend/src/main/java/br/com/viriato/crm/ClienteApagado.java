package br.com.viriato.crm;

/** Publicado antes de apagar uma empresa atendida, para o cofre e as procurações removerem os arquivos. */
public record ClienteApagado(Long tenantId, Long clienteId) {}
