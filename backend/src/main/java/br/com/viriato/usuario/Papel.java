package br.com.viriato.usuario;

public enum Papel {
    ADMIN(2),    // gerencia empresa, usuários e fluxos
    GESTOR(1),   // gerencia fluxos e atribui processos
    USUARIO(0);  // opera processos

    private final int nivel;

    Papel(int nivel) { this.nivel = nivel; }

    /** Verdadeiro se este papel tem, no mínimo, o nível exigido (administrador > gestor > usuário). */
    public boolean atende(Papel minimo) { return nivel >= minimo.nivel; }
}
