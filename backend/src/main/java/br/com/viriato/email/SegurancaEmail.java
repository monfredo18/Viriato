package br.com.viriato.email;

/** NENHUMA: sem criptografia (só rede de confiança). STARTTLS: começa simples e passa a criptografar (porta 587). SSL: criptografado desde o início (porta 465). */
public enum SegurancaEmail { NENHUMA, STARTTLS, SSL }
