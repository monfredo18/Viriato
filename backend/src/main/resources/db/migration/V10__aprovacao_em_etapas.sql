-- Quem pode mover um processo PARA a etapa: USUARIO (qualquer responsável), GESTOR (gestor ou administrador) ou ADMIN.
ALTER TABLE etapa ADD COLUMN papel_minimo VARCHAR(10) NOT NULL DEFAULT 'USUARIO';

-- Pedidos de aprovação: quem não pode mover para a etapa pede, e quem pode aprova ou recusa.
CREATE TABLE processo_aprovacao (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id        BIGINT      NOT NULL,
  processo_id      BIGINT      NOT NULL,
  etapa_origem_id  BIGINT      NOT NULL,
  etapa_destino_id BIGINT      NOT NULL,
  solicitado_por   BIGINT      NULL,
  solicitado_em    TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  comentario       TEXT        NULL,
  status           VARCHAR(12) NOT NULL,
  decidido_por     BIGINT      NULL,
  decidido_em      TIMESTAMP   NULL,
  motivo           TEXT        NULL,
  CONSTRAINT fk_aprov_processo FOREIGN KEY (processo_id) REFERENCES processo (id),
  CONSTRAINT fk_aprov_origem FOREIGN KEY (etapa_origem_id) REFERENCES etapa (id) ON DELETE CASCADE,
  CONSTRAINT fk_aprov_destino FOREIGN KEY (etapa_destino_id) REFERENCES etapa (id) ON DELETE CASCADE
);
CREATE INDEX ix_aprov_processo ON processo_aprovacao (processo_id, status);
