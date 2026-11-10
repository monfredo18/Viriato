package br.com.viriato.documento;

import java.io.InputStream;

/** Abstração do storage de arquivos (S3 em produção; substituível em testes). */
public interface ArmazenamentoArquivos {
    void salvar(String chave, InputStream conteudo, long tamanho, String contentType);
    InputStream abrir(String chave);
    void remover(String chave);
}
