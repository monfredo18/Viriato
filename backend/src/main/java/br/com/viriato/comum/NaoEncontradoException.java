package br.com.viriato.comum;

/** Recurso inexistente ou de outro tenant (HTTP 404, sem vazar a existência). */
public class NaoEncontradoException extends RuntimeException {
    public NaoEncontradoException(String recurso) { super(recurso + " não encontrado(a)"); }
}
