package br.com.viriato.fluxo;

import br.com.viriato.cadastro.Entrega;
import br.com.viriato.cadastro.EntregaRepository;
import br.com.viriato.seguranca.Contexto;
import br.com.viriato.seguranca.UsuarioLogado;
import br.com.viriato.usuario.Papel;
import br.com.viriato.usuario.Usuario;
import br.com.viriato.usuario.UsuarioRepository;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Quem enxerga e quem altera cada fluxo. O fluxo pertence à entrega (ou às entregas) que o usam, então herda a regra
 * delas: o administrador vê e altera todos; gestor e usuário veem o fluxo das entregas gerais e das exclusivas do próprio
 * setor; o gestor só altera fluxos cujas entregas são TODAS exclusivas do próprio setor. Fluxo sem entrega é só do administrador.
 * Usado nas anotações {@code @PreAuthorize("@permissaoFluxo.pode(#fluxoId)")} dos controllers.
 */
@Component("permissaoFluxo")
public class PermissaoFluxo {
    private final EntregaRepository entregas;
    private final UsuarioRepository usuarios;

    public PermissaoFluxo(EntregaRepository entregas, UsuarioRepository usuarios) {
        this.entregas = entregas;
        this.usuarios = usuarios;
    }

    private Long setorDe(UsuarioLogado quem) {
        return usuarios.findByIdAndTenantId(quem.id(), quem.tenantId()).map(Usuario::getSetorId).orElse(null);
    }

    @Transactional(readOnly = true)
    public boolean visivel(Long fluxoId) {
        UsuarioLogado quem = Contexto.atual();
        if (quem.papel() == Papel.ADMIN) return true;
        List<Entrega> usos = entregas.findByTenantIdAndFluxoIdOrderByNome(quem.tenantId(), fluxoId);
        Long meu = setorDe(quem);
        return usos.stream().anyMatch(e -> e.getSetorDonoId() == null || e.getSetorDonoId().equals(meu));
    }

    /** Pode alterar (etapas, requisitos, automações, situação, apagar). */
    @Transactional(readOnly = true)
    public boolean pode(Long fluxoId) {
        UsuarioLogado quem = Contexto.atual();
        if (quem.papel() == Papel.ADMIN) return true;
        if (quem.papel() != Papel.GESTOR) return false;
        List<Entrega> usos = entregas.findByTenantIdAndFluxoIdOrderByNome(quem.tenantId(), fluxoId);
        Long meu = setorDe(quem);
        return meu != null && !usos.isEmpty() && usos.stream().allMatch(e -> meu.equals(e.getSetorDonoId()));
    }
}
