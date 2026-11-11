-- Cadastros do catálogo de cada empresa do sistema (tenant):
--   cliente  = empresa atendida (para quem o processo é feito)
--   setor    = departamento
--   entrega  = o que pode ser entregue; cada entrega define o fluxo do processo
--   setor_entrega = quais entregas cada setor pode fazer

CREATE TABLE cliente (
  id        BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT       NOT NULL,
  nome      VARCHAR(150) NOT NULL,
  ativo     BOOLEAN      NOT NULL DEFAULT TRUE,
  criado_em TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_cliente_nome UNIQUE (tenant_id, nome),
  CONSTRAINT fk_cliente_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id)
);

CREATE TABLE setor (
  id        BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT       NOT NULL,
  nome      VARCHAR(100) NOT NULL,
  ativo     BOOLEAN      NOT NULL DEFAULT TRUE,
  CONSTRAINT uk_setor_nome UNIQUE (tenant_id, nome),
  CONSTRAINT fk_setor_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id)
);

CREATE TABLE entrega (
  id        BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT       NOT NULL,
  nome      VARCHAR(150) NOT NULL,
  fluxo_id  BIGINT       NOT NULL,
  ativo     BOOLEAN      NOT NULL DEFAULT TRUE,
  CONSTRAINT uk_entrega_nome UNIQUE (tenant_id, nome),
  CONSTRAINT fk_entrega_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
  CONSTRAINT fk_entrega_fluxo FOREIGN KEY (fluxo_id) REFERENCES fluxo (id)
);

-- A empresa (tenant) é derivada do setor; o serviço garante que setor e entrega são da mesma empresa.
CREATE TABLE setor_entrega (
  setor_id   BIGINT NOT NULL,
  entrega_id BIGINT NOT NULL,
  PRIMARY KEY (setor_id, entrega_id),
  CONSTRAINT fk_se_setor FOREIGN KEY (setor_id) REFERENCES setor (id),
  CONSTRAINT fk_se_entrega FOREIGN KEY (entrega_id) REFERENCES entrega (id)
);

-- Setor do funcionário (opcional: o administrador pode não ter setor).
ALTER TABLE usuario ADD COLUMN setor_id BIGINT NULL;
ALTER TABLE usuario ADD CONSTRAINT fk_usuario_setor FOREIGN KEY (setor_id) REFERENCES setor (id);
