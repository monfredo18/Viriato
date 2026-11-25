package br.com.viriato.ged;

import br.com.viriato.cadastro.Cliente;
import br.com.viriato.cadastro.ClienteRepository;
import br.com.viriato.agente.AgenteService;
import br.com.viriato.cadastro.TenantIds;
import br.com.viriato.comum.Datas;
import br.com.viriato.comum.NaoEncontradoException;
import br.com.viriato.comum.NegocioException;
import br.com.viriato.crm.CertificadoService;
import br.com.viriato.crm.ClienteCertificado;
import br.com.viriato.crm.Enums.StatusContratual;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Baixa da SEFAZ (Distribuição DF-e) as notas emitidas contra o CNPJ da empresa atendida, usando o certificado instalado no
 * computador de um usuário (a chamada com a chave privada é feita pelo agente local), e as importa pelo mesmo caminho dos XMLs
 * enviados à mão. Guarda o último NSU e respeita a espera de 1 hora que a SEFAZ
 * exige quando não há documentos novos.
 */
@Service
public class DfeService {
    private static final Logger log = LoggerFactory.getLogger(DfeService.class);
    static final int RODADAS = 5;

    public record Resultado(String status, String mensagem, int importadas, int duplicadas, int resumosIgnorados, String ultimoNsu) {}
    public record Estado(boolean habilitado, String ultimoNsu, Instant ultimaConsulta, Instant proximaConsulta, String status, String mensagem) {}

    private final SefazDfeConnector conector;
    private final ClienteRepository clientes;
    private final CertificadoService certificados;
    private final AgenteService agentes;
    private final DfeControleRepository controles;
    private final ImportacaoXmlService importacao;
    private final TenantIds tenants;

    public DfeService(SefazDfeConnector conector, ClienteRepository clientes, CertificadoService certificados, AgenteService agentes,
                      DfeControleRepository controles, ImportacaoXmlService importacao, TenantIds tenants) {
        this.conector = conector;
        this.clientes = clientes;
        this.certificados = certificados;
        this.agentes = agentes;
        this.controles = controles;
        this.importacao = importacao;
        this.tenants = tenants;
    }

    public Estado estado(Long tenantId, Long clienteId) {
        clientes.findByIdAndTenantId(clienteId, tenantId).orElseThrow(() -> new NaoEncontradoException("Empresa"));
        DfeControle c = controles.findByClienteIdAndTenantId(clienteId, tenantId).orElse(null);
        return new Estado(conector.habilitado(), c == null ? "0" : c.getUltimoNsu(), c == null ? null : c.getUltimaConsulta(), c == null ? null : c.getProximaConsulta(), c == null ? null : c.getStatus(), c == null ? null : c.getMensagem());
    }

    /** Consulta de uma empresa, pedida por uma pessoa. */
    public Resultado consultar(Long tenantId, Long usuarioId, Long clienteId) {
        Cliente c = clientes.findByIdAndTenantId(clienteId, tenantId).orElseThrow(() -> new NaoEncontradoException("Empresa"));
        return consultar(tenantId, usuarioId, c);
    }

    public Resultado consultar(Long tenantId, Long usuarioId, Cliente c) {
        if (!conector.habilitado()) throw new NegocioException("A consulta automática à SEFAZ está desligada neste ambiente (VIRIATO_SEFAZ_HABILITADO)");
        if (c.getCnpj() == null) throw new NegocioException("A empresa não tem CNPJ cadastrado");
        ClienteCertificado cert = certificados.paraUso(tenantId, c.getId());   // um certificado válido, num computador com o agente conectado
        DfeControle ctl = controles.findByClienteIdAndTenantId(c.getId(), tenantId).orElseGet(() -> { DfeControle n = new DfeControle(); n.setTenantId(tenantId); n.setClienteId(c.getId()); return n; });
        if (ctl.getProximaConsulta() != null && ctl.getProximaConsulta().isAfter(Instant.now())) {
            throw new NegocioException("A SEFAZ pede para esperar antes de consultar de novo (próxima consulta a partir de " + Datas.DATA_HORA.format(ctl.getProximaConsulta().atZone(Datas.ZONA)) + ")");
        }
        certificados.registrarUso(tenantId, usuarioId, cert, "Consulta de notas na SEFAZ (DF-e)");   // fica na auditoria
        SefazDfeConnector.Postador postador = (url, tipo, corpo) -> agentes.executarHttps(tenantId, cert.getId(), url, tipo, corpo);
        int importadas = 0, duplicadas = 0, resumos = 0;
        String status = "OK", mensagem = null;
        try {
            for (int i = 0; i < RODADAS; i++) {
                SefazDfeConnector.Resposta r = conector.consultar(postador, c.getCnpj(), c.getUf(), ctl.getUltimoNsu());
                if (r.ultimoNsu() != null) ctl.setUltimoNsu(r.ultimoNsu());
                mensagem = r.codigoStatus() + " — " + r.motivo();
                if ("137".equals(r.codigoStatus()) || "656".equals(r.codigoStatus())) {   // nada novo, ou consumo indevido: esperar 1 hora
                    status = "137".equals(r.codigoStatus()) ? "SEM_DOCUMENTOS" : "ESPERAR";
                    ctl.setProximaConsulta(Instant.now().plus(Duration.ofHours(1)));
                    break;
                }
                if (!"138".equals(r.codigoStatus())) { status = "ERRO"; break; }
                for (SefazDfeConnector.DocumentoDfe d : r.documentos()) {
                    if (d.esquema() == null || !d.esquema().startsWith("procNFe")) { resumos++; continue; }   // resumos e eventos não são a nota
                    try {
                        if (importacao.importarUma(tenantId, usuarioId, c, "NFe-" + d.nsu() + ".xml", d.xml(), "SEFAZ").isPresent()) importadas++; else duplicadas++;
                    } catch (NegocioException e) {
                        log.warn("Nota do NSU {} da empresa {} não importada: {}", d.nsu(), c.getId(), e.getMessage());
                    }
                }
                if (r.ultimoNsu() == null || r.maxNsu() == null || r.ultimoNsu().equals(r.maxNsu())) break;
            }
        } catch (IOException e) {
            status = "ERRO";
            mensagem = e.getMessage();
        }
        ctl.setUltimaConsulta(Instant.now());
        ctl.setStatus(status);
        ctl.setMensagem(mensagem == null ? null : mensagem.length() > 300 ? mensagem.substring(0, 300) : mensagem);
        controles.save(ctl);
        return new Resultado(status, mensagem, importadas, duplicadas, resumos, ctl.getUltimoNsu());
    }

    /** Agendador (desligado por padrão): percorre as empresas com certificado A1 válido e CNPJ. */
    @Scheduled(cron = "${viriato.sefaz.cron:-}")
    public void agendado() {
        if (!conector.habilitado()) return;
        for (Long tenantId : tenants.todos()) {
            for (Cliente c : clientes.findByTenantIdOrderByNome(tenantId)) {
                if (!c.isAtivo() || c.getStatusContratual() != StatusContratual.ATIVO || c.getCnpj() == null) continue;
                try {
                    consultar(tenantId, null, c);
                } catch (NegocioException e) {
                    // sem certificado válido ou em espera: normal, segue para a próxima
                } catch (RuntimeException e) {
                    log.error("Falha na consulta à SEFAZ da empresa {}", c.getId(), e);
                }
            }
        }
    }
}
