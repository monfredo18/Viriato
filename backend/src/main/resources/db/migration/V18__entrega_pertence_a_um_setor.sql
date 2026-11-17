-- Cada entrega pertence a um único setor (o dono): só ele a vê e usa.
-- Entregas gerais antigas (sem dono) ficam com o primeiro setor que as fazia (ou, sem nenhum, o primeiro setor da empresa).
-- Setores que dividiam a mesma entrega precisam passar a ter a sua própria.
UPDATE entrega
   SET setor_dono_id = COALESCE((SELECT MIN(se.setor_id) FROM setor_entrega se WHERE se.entrega_id = entrega.id),
                                (SELECT MIN(s.id) FROM setor s WHERE s.tenant_id = entrega.tenant_id))
 WHERE setor_dono_id IS NULL;

DELETE FROM setor_entrega
 WHERE setor_id <> (SELECT e.setor_dono_id FROM entrega e WHERE e.id = setor_entrega.entrega_id);

INSERT INTO setor_entrega (setor_id, entrega_id)
SELECT e.setor_dono_id, e.id FROM entrega e
 WHERE e.setor_dono_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM setor_entrega se WHERE se.setor_id = e.setor_dono_id AND se.entrega_id = e.id);
