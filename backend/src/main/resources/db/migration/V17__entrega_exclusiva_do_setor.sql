-- Entrega criada por gestor pertence ao setor dele (exclusiva): só esse setor a enxerga e usa.
-- Entrega sem dono (criada pelo administrador) continua valendo para qualquer setor que a faça.
ALTER TABLE entrega ADD COLUMN setor_dono_id BIGINT NULL;
CREATE INDEX ix_entrega_setor_dono ON entrega (setor_dono_id);
