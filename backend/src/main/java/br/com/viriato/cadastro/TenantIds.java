package br.com.viriato.cadastro;

import br.com.viriato.tenant.TenantRepository;
import java.util.List;
import org.springframework.stereotype.Component;

/** Identificadores das empresas do sistema ativas (para as tarefas agendadas que rodam para todas). */
@Component
public class TenantIds {
    private final TenantRepository tenants;

    public TenantIds(TenantRepository tenants) { this.tenants = tenants; }

    public List<Long> todos() { return tenants.findAll().stream().filter(t -> t.isAtivo()).map(t -> t.getId()).toList(); }
}
