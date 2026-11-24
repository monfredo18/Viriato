package br.com.viriato.crm;


/** Validação e máscara de CNPJ e CPF (algoritmo dos dígitos verificadores). Só dígitos são guardados. */
public final class Documentos {
    private Documentos() {}

    public static String digitos(String s) { return s == null ? "" : s.replaceAll("\\D", ""); }

    public static boolean cnpjValido(String bruto) {
        String c = digitos(bruto);
        if (c.length() != 14 || todosIguais(c)) return false;
        int[] p1 = {5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2}, p2 = {6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
        return dv(c, 12, p1) == c.charAt(12) - '0' && dv(c, 13, p2) == c.charAt(13) - '0';
    }

    public static boolean cpfValido(String bruto) {
        String c = digitos(bruto);
        if (c.length() != 11 || todosIguais(c)) return false;
        int[] p1 = {10, 9, 8, 7, 6, 5, 4, 3, 2}, p2 = {11, 10, 9, 8, 7, 6, 5, 4, 3, 2};
        return dv(c, 9, p1) == c.charAt(9) - '0' && dv(c, 10, p2) == c.charAt(10) - '0';
    }

    public static String formatarCnpj(String c) {
        return c == null || c.length() != 14 ? c : c.substring(0, 2) + "." + c.substring(2, 5) + "." + c.substring(5, 8) + "/" + c.substring(8, 12) + "-" + c.substring(12);
    }

    /** ***.456.789-** : esconde o começo e o fim, o suficiente para reconhecer sem expor o CPF. */
    public static String mascararCpf(String cpf) {
        String c = digitos(cpf);
        return "***." + c.substring(3, 6) + "." + c.substring(6, 9) + "-**";
    }

    private static int dv(String c, int n, int[] pesos) {
        int soma = 0;
        for (int i = 0; i < n; i++) soma += (c.charAt(i) - '0') * pesos[i];
        int r = soma % 11;
        return r < 2 ? 0 : 11 - r;
    }

    private static boolean todosIguais(String c) { return c.chars().distinct().count() == 1; }

}
