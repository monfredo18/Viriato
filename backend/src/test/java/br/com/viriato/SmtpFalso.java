package br.com.viriato;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Servidor SMTP de teste: guarda as mensagens e, se tiver usuário, exige AUTH PLAIN ou LOGIN com a senha certa. */
final class SmtpFalso implements AutoCloseable {
    record Mensagem(String de, List<String> para, String bruto) {}

    final List<Mensagem> mensagens = new CopyOnWriteArrayList<>();
    final List<String> tentativasDeLogin = new CopyOnWriteArrayList<>();
    private final ServerSocket servidor;
    private final String usuario;
    private final String senha;

    SmtpFalso(String usuario, String senha) throws IOException {
        this.usuario = usuario;
        this.senha = senha;
        this.servidor = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
        Thread t = new Thread(this::aceitar, "smtp-falso");
        t.setDaemon(true);
        t.start();
    }

    int porta() { return servidor.getLocalPort(); }

    private void aceitar() {
        while (!servidor.isClosed()) {
            try {
                Socket s = servidor.accept();
                Thread t = new Thread(() -> atender(s), "smtp-falso-conexao");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                return;
            }
        }
    }

    private void atender(Socket s) {
        try (s; var in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
             var out = new PrintWriter(s.getOutputStream(), false, StandardCharsets.UTF_8)) {
            Responder r = (texto) -> { out.print(texto + "\r\n"); out.flush(); };
            r.dizer("220 smtp-falso pronto");
            boolean autenticado = usuario == null;
            String de = "";
            List<String> para = new ArrayList<>();
            String linha;
            while ((linha = in.readLine()) != null) {
                String maiuscula = linha.toUpperCase();
                if (maiuscula.startsWith("EHLO") || maiuscula.startsWith("HELO")) {
                    if (usuario == null) r.dizer("250 smtp-falso");
                    else { r.dizer("250-smtp-falso"); r.dizer("250 AUTH PLAIN LOGIN"); }
                } else if (maiuscula.startsWith("AUTH PLAIN")) {
                    String b64 = linha.length() > 11 ? linha.substring(11).trim() : null;
                    if (b64 == null || b64.isEmpty()) { r.dizer("334 "); b64 = in.readLine(); }
                    String[] partes = new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8).split("\0", -1);
                    autenticado = conferir(partes.length > 2 ? partes[1] : "", partes.length > 2 ? partes[2] : "", r);
                } else if (maiuscula.startsWith("AUTH LOGIN")) {
                    r.dizer("334 VXNlcm5hbWU6");
                    String u = new String(Base64.getDecoder().decode(in.readLine()), StandardCharsets.UTF_8);
                    r.dizer("334 UGFzc3dvcmQ6");
                    String p = new String(Base64.getDecoder().decode(in.readLine()), StandardCharsets.UTF_8);
                    autenticado = conferir(u, p, r);
                } else if (maiuscula.startsWith("MAIL FROM")) {
                    if (!autenticado) { r.dizer("530 5.7.0 autenticacao necessaria"); continue; }
                    de = linha.substring(linha.indexOf('<') + 1, linha.indexOf('>'));
                    r.dizer("250 ok");
                } else if (maiuscula.startsWith("RCPT TO")) {
                    para.add(linha.substring(linha.indexOf('<') + 1, linha.indexOf('>')));
                    r.dizer("250 ok");
                } else if (maiuscula.equals("DATA")) {
                    r.dizer("354 termine com ponto");
                    StringBuilder corpo = new StringBuilder();
                    String l;
                    while ((l = in.readLine()) != null && !l.equals(".")) corpo.append(l.startsWith("..") ? l.substring(1) : l).append('\n');
                    mensagens.add(new Mensagem(de, new ArrayList<>(para), corpo.toString()));
                    para.clear();
                    r.dizer("250 ok guardado");
                } else if (maiuscula.equals("QUIT")) {
                    r.dizer("221 tchau");
                    return;
                } else {
                    r.dizer("250 ok");
                }
            }
        } catch (IOException ignorado) {
            // conexão encerrada pelo cliente
        }
    }

    private boolean conferir(String u, String p, Responder r) {
        tentativasDeLogin.add(u + ":" + p);
        boolean ok = usuario.equals(u) && senha.equals(p);
        r.dizer(ok ? "235 2.7.0 autenticado" : "535 5.7.8 usuario ou senha invalidos");
        return ok;
    }

    private interface Responder { void dizer(String texto); }

    @Override
    public void close() throws IOException { servidor.close(); }
}
