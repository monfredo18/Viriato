-- O acesso passa a ser por nome e senha: o e-mail deixa de ser obrigatório (contas antigas o mantêm).
ALTER TABLE usuario MODIFY COLUMN email VARCHAR(190) NULL;
-- Senha inicial/zerada: o funcionário precisa criar uma senha própria no primeiro acesso.
ALTER TABLE usuario ADD COLUMN senha_provisoria BOOLEAN NOT NULL DEFAULT FALSE;
-- Zerar a senha encerra as sessões abertas: cada token carrega a versão da sessão vigente.
ALTER TABLE usuario ADD COLUMN versao_sessao INT NOT NULL DEFAULT 0;
CREATE INDEX ix_usuario_nome ON usuario (nome);
