package br.com.viriato.agente;

import java.io.FileInputStream;
import java.security.KeyStore;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

/** Em quem o agente confia ao falar com a SEFAZ e com o Viriato: no Windows, nas autoridades do próprio Windows (inclui a cadeia ICP-Brasil). */
final class Confianca {
    private Confianca() {}

    static boolean windows() { return System.getProperty("os.name", "").toLowerCase().contains("windows"); }

    static TrustManager[] gerenciadores(Configuracao cfg) throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        if (cfg != null && cfg.truststore() != null) {
            KeyStore ts = KeyStore.getInstance(cfg.truststore().toLowerCase().endsWith(".jks") ? "JKS" : "PKCS12");
            try (FileInputStream in = new FileInputStream(cfg.truststore())) { ts.load(in, cfg.truststoreSenha() == null ? null : cfg.truststoreSenha().toCharArray()); }
            tmf.init(ts);
        } else if (windows()) {
            KeyStore raiz = KeyStore.getInstance("Windows-ROOT");
            raiz.load(null, null);
            tmf.init(raiz);
        } else {
            tmf.init((KeyStore) null);
        }
        return tmf.getTrustManagers();
    }
}
