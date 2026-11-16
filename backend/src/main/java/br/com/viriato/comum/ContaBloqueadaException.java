package br.com.viriato.comum;

/** Conta bloqueada por excesso de senhas erradas: só o suporte (administrador ou gestor) desbloqueia. */
public class ContaBloqueadaException extends RuntimeException {
    public ContaBloqueadaException() { super("Conta bloqueada por excesso de tentativas. Entre em contato com o suporte."); }
}
