package br.com.viriato.ged;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;

/** O que o leitor de XML extrai de uma nota fiscal eletrônica. */
public record NotaParseada(String modelo, String chave, String numero, String serie, LocalDate dataEmissao, String cnpjEmitente, String nomeEmitente,
                           String cnpjDestinatario, String nomeDestinatario, BigDecimal valorTotal, BigDecimal icms, BigDecimal ipi, BigDecimal pis,
                           BigDecimal cofins, BigDecimal iss, Set<String> cfops, Set<String> ncms) {}
