package br.com.viriato.comum;

/** A conta usa verificação em duas etapas e o login veio sem o código do autenticador. */
public class CodigoNecessarioException extends RuntimeException {
    public CodigoNecessarioException() { super("Informe o código do aplicativo autenticador"); }
}
