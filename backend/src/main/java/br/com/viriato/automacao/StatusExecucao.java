package br.com.viriato.automacao;

/**
 * A própria execução é a fila: PENDENTE (esperando a vez ou a próxima tentativa), PROCESSANDO (reservada por uma instância),
 * OK (feito) ou FALHA (esgotou as tentativas ou não adianta tentar de novo).
 */
public enum StatusExecucao { PENDENTE, PROCESSANDO, OK, FALHA }
