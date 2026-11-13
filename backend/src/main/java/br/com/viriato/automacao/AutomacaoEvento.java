package br.com.viriato.automacao;

/** Publicado pelo processo; as regras só rodam depois que a transação que o gerou for confirmada. */
public record AutomacaoEvento(Long tenantId, Long processoId, EventoAutomacao evento, Long etapaId, Long usuarioId) {}
