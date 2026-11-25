package br.com.viriato.agente;

import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * Lê o repositório "Pessoal" do usuário do Windows (o mesmo que o Internet Explorer/Edge e os programas de assinatura usam), pelo
 * provedor SunMSCAPI do Java. Vale para o A1 instalado e para o A3 em token ou cartão (o próprio Windows/driver pede o PIN). A chave
 * privada nunca é exportada: o Java só pede ao Windows para assinar.
 */
final class RepositorioWindows implements RepositorioDeCertificados {
    private static final Duration VENCIDO_HA_MAIS_DE = Duration.ofDays(90);

    @Override
    public KeyStore repositorio() throws Exception {
        KeyStore ks = KeyStore.getInstance("Windows-MY");
        ks.load(null, null);
        return ks;
    }

    @Override public char[] senhaDaChave() { return null; }

    @Override
    public List<Local> listar() throws Exception {
        KeyStore ks = repositorio();
        List<Local> r = new ArrayList<>();
        Date limite = Date.from(Instant.now().minus(VENCIDO_HA_MAIS_DE));
        for (String alias : Collections.list(ks.aliases())) {
            if (!ks.isKeyEntry(alias) || !(ks.getCertificate(alias) instanceof X509Certificate x)) continue;   // só o que tem chave privada
            if (x.getNotAfter().before(limite)) continue;                                                      // vencido há muito tempo: não interessa
            r.add(new Local(RepositorioDeCertificados.thumbprint(x), alias, x));
        }
        return r;
    }
}
