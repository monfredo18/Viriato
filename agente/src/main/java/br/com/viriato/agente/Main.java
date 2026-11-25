package br.com.viriato.agente;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * Agente local do Viriato. Comandos:
 * <ul>
 *   <li>{@code parear <endereço do Viriato> <código> [nome do computador]}: liga este computador à sua conta (o código é gerado em Certificados e agente);</li>
 *   <li>{@code executar} (padrão): fica ligado, informa os certificados e atende os pedidos do Viriato;</li>
 *   <li>{@code certificados}: mostra os certificados que o agente enxerga aqui (não precisa de pareamento).</li>
 * </ul>
 */
public final class Main {
    static final String VERSAO = versao();

    private Main() {}

    public static void main(String[] args) throws Exception {
        String comando = args.length == 0 ? "executar" : args[0];
        Path pasta = Configuracao.pasta();
        log(pasta);
        switch (comando) {
            case "parear" -> parear(args, pasta);
            case "executar" -> executar(pasta);
            case "certificados" -> certificados();
            case "versao", "--version", "-v" -> System.out.println("Agente do Viriato " + VERSAO);
            default -> {
                System.out.println("Agente do Viriato " + VERSAO + "\n  parear <endereço do Viriato> <código> [nome do computador]\n  executar\n  certificados");
                if (!comando.equals("ajuda") && !comando.equals("--help")) System.exit(2);
            }
        }
    }

    private static void parear(String[] args, Path pasta) throws Exception {
        if (args.length < 3) {
            System.out.println("Uso: parear <endereço do Viriato> <código> [nome do computador]");
            System.exit(2);
        }
        String nome = args.length > 3 ? args[3] : nomeDoComputador();
        Configuracao novo = new Configuracao(args[1], null, nome, Configuracao.HOSTS_PADRAO, null, null);
        String token = new ClienteViriato(args[1], null, Confianca.gerenciadores(novo)).parear(args[2], nome, VERSAO);
        new Configuracao(args[1], token, nome, Configuracao.HOSTS_PADRAO, null, null).salvar(pasta);
        System.out.println("Pronto: este computador (" + nome + ") está ligado ao Viriato. Agora inicie o agente com: executar");
    }

    private static void executar(Path pasta) throws Exception {
        Configuracao cfg = Configuracao.carregar(pasta);
        if (cfg.token() == null || cfg.servidor() == null) throw new IllegalStateException("Configuração incompleta: refaça o pareamento");
        RepositorioDeCertificados repo = repositorio();
        var confianca = Confianca.gerenciadores(cfg);
        Servico s = new Servico(repo, new ClienteViriato(cfg.servidor(), cfg.token(), confianca), new ExecutorHttps(repo, cfg.hostsPermitidos(), true, confianca, Duration.ofSeconds(60)), VERSAO);
        Logger.getLogger("viriato.agente").info("Agente " + VERSAO + " em " + cfg.nome() + " ligado a " + cfg.servidor());
        s.executar(Duration.ofSeconds(5));
    }

    private static void certificados() throws Exception {
        var lista = repositorio().listar();
        if (lista.isEmpty()) System.out.println("Nenhum certificado com chave privada encontrado neste computador.");
        for (var l : lista) {
            System.out.println(l.certificado().getSubjectX500Principal().getName() + "\n  validade até " + l.certificado().getNotAfter() + "\n  impressão digital " + l.thumbprint());
        }
    }

    /** O repositório do Windows; só para teste e desenvolvimento aceita um .pfx local (VIRIATO_AGENTE_PFX). */
    static RepositorioDeCertificados repositorio() {
        String pfx = System.getenv("VIRIATO_AGENTE_PFX");
        if (pfx != null && !pfx.isBlank()) {
            String senha = System.getenv("VIRIATO_AGENTE_PFX_SENHA");
            return new RepositorioPkcs12(Path.of(pfx), senha == null ? new char[0] : senha.toCharArray());
        }
        if (Confianca.windows()) return new RepositorioWindows();
        throw new IllegalStateException("Este agente lê o repositório de certificados do Windows. Neste sistema operacional só funciona em modo de teste (VIRIATO_AGENTE_PFX).");
    }

    private static String nomeDoComputador() {
        String n = System.getenv("COMPUTERNAME");
        if (n != null && !n.isBlank()) return n;
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "Computador";
        }
    }

    private static void log(Path pasta) {
        try {
            Files.createDirectories(pasta);
            FileHandler fh = new FileHandler(pasta.resolve("agente-%g.log").toString(), 1_000_000, 3, true);
            fh.setFormatter(new SimpleFormatter());
            Logger l = Logger.getLogger("viriato.agente");
            l.addHandler(fh);
            l.setLevel(Level.INFO);
        } catch (Exception e) {
            // sem arquivo de log: segue só no console
        }
    }

    private static String versao() {
        String v = Main.class.getPackage() == null ? null : Main.class.getPackage().getImplementationVersion();
        return v == null ? "0.1.0" : v;
    }
}
