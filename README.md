# Viriato — Controle de Processos

Sistema web multi-empresa (SaaS) para cadastrar, acompanhar e automatizar processos com fluxos configuráveis, prazos, documentos, notificações, chat de ajuda e relatórios.

| Camada | Tecnologia |
|---|---|
| Frontend | React 18 + TypeScript + Vite |
| Backend | Java 21 + Spring Boot 3 (monolito modular) |
| Banco | MySQL 8 (migrações com Flyway) |
| Arquivos | AWS S3 (S3 local em dev) |
| Notificações | In-app (sininho) |
| Ajuda | Chat interno com os administradores (botão "Ajuda") |
| Foto de perfil | Mesmo armazenamento dos documentos (S3 ou disco) |
| Relatórios | PDF gerado no servidor (OpenPDF) |
| Infra | Docker / Docker Compose |

> 📄 **[Relatório completo do sistema (PDF)](docs/relatorio-viriato.pdf)**: como funciona, arquitetura, regras, segurança, limitações conhecidas e próximos passos. A fonte está em `docs/relatorio/` (regerar: `node docs/relatorio/gerar-pdf.js`).

## Rodando localmente

```bash
docker compose up --build
```

- App: http://localhost:3000 — entre com o administrador inicial (nome `Administrador`, senha `trocar-12345`, definidos por `ADMIN_NOME`/`ADMIN_SENHA` em `docker-compose.yml` ou `.env`). No primeiro acesso o sistema pede que ele crie a própria senha.
- Swagger/OpenAPI: http://localhost:8080/swagger-ui.html (no `docker-compose.yml` vem ligado; por padrão o sistema o deixa desligado, ver `SWAGGER_ATIVO`)

Desenvolvimento sem Docker para o código:

```bash
docker compose up -d mysql s3   # dependências
cd backend && VIRIATO_JWT_PERMITIR_PADRAO=true mvn spring-boot:run   # API em :8080 (segredo de exemplo só em dev)
cd frontend && npm install && npm run dev   # UI em :5173 (proxy para :8080)
```

Testes: `cd backend && mvn test` (119 testes de integração; usam H2 em modo MySQL, não precisam de Docker).

## Arquitetura

```
backend/src/main/java/br/com/viriato/
  seguranca/   JWT, sessões revogadas, filtro, cabeçalhos, cifra e 2FA (TOTP), CORS
  auditoria/   registro de eventos de segurança e administração
  usuario/     login por nome, senha provisória, primeiro administrador, usuários e papéis
  cadastro/    empresas atendidas, setores e entregas
  fluxo/       fluxos de cada entrega: etapas, requisitos e transições, com a regra de quem vê e altera
  processo/    processos, movimentação, histórico, atribuição, cancelamento, aprovação
  automacao/   regras por etapa: ficha em PDF e webhook
  indicador/   painel de indicadores
  relatorio/   relatórios de processos em PDF
  documento/   upload/download (S3 ou disco), nomes sanitizados
  tenant/      empresas (tenants)
  notificacao/ avisos no sistema + agendador de prazos
  ajuda/       chat de ajuda entre as pessoas da empresa e os administradores
  comum/       tratamento de erros, datas
```

### Multi-tenant
- Toda tabela de negócio tem `tenant_id`.
- O `tenant_id` vem **somente do JWT**, nunca do corpo da requisição.
- Toda consulta filtra por `tenant_id`; recurso de outra empresa responde **404**.
- Coberto por testes (`FluxoCompletoTest`).

### Interface
Menu lateral (Início, Processos, Painel, Cadastros, Entregas, Usuários, Relatórios e **Minhas tarefas**, com a contagem dos seus processos em aberto) e barra superior com busca de processos, botão **Ajuda**, avisos, tema claro/escuro e perfil. A tela **Início** resume em andamento, atrasados, concluídos, tempo médio, carga, processos recentes e prazos próximos. No celular o menu vira uma gaveta. O indicador **Sistema online** do rodapé do menu é real: a tela consulta `GET /api/auth/saude` a cada 30 s e mostra vermelho ("Sem conexão com o servidor") quando a API não responde.

- **Foto de perfil:** clicando no próprio nome abre-se *Meu perfil*, onde se escolhe, troca ou remove a foto (JPEG, PNG ou WebP, até 2 MB; o tipo é conferido pelo conteúdo do arquivo e o navegador reduz a imagem antes de enviar). O administrador pode remover a foto de outra pessoa pela API.
- **Ajuda:** gestor e usuário têm uma conversa própria com os administradores; os administradores veem a lista de conversas da empresa e qualquer um responde. Aviso de mensagem nova no botão; a janela atualiza sozinha (consulta a cada poucos segundos).

### Cadastros e processos
- **Empresas atendidas** (para quem o processo é feito), **setores** e **entregas** são cadastros da empresa.
- Cada empresa atendida tem **número** (código interno de 1 a 10 dígitos, sem repetir) e **nome**. O **número do processo é o número da empresa**: processos da mesma empresa compartilham o número.
- **Cada setor tem as suas entregas** e não vê nem usa as dos outros; cada **entrega** tem o seu fluxo (etapas) do processo.
- **Entregas e fluxos num lugar só:** a tela **Entregas** mostra cada entrega com o fluxo dela (etapas, requisitos, aprovação e automações). Para o **administrador**, ela tem uma **sub-aba por setor**, cada uma com as entregas daquele setor (e o formulário para criar mais ali); gestor e usuário veem só as do próprio setor. Criar uma entrega já cria o fluxo dela, com as etapas informadas (ou as padrão: Abertura, Análise, Revisão e Concluído); apagar a entrega apaga o fluxo junto. Depois de criada, dá para **criar novas etapas** (entram antes da conclusão), renomear e remover (as intermediárias, sem processo em andamento); o fluxo segue em sequência.
- **Só o administrador cria, altera e apaga setores.** Toda entrega pertence a um setor: o administrador escolhe o setor; a que o **gestor** cria vai direto para o dele. O gestor altera e apaga as entregas do próprio setor e as dos outros ele nem vê. Apagar o setor apaga as entregas dele. O nome da entrega é único na empresa.
- **Migração das entregas antigas (V18):** entregas que eram compartilhadas por vários setores ficam com o primeiro setor que as fazia; os outros setores precisam criar a sua própria entrega (os processos antigos deles continuam existindo).
- Novo processo: administrador informa empresa, departamento, entrega e responsável; gestor, empresa, entrega e responsável (departamento é o dele); usuário, só empresa e entrega (departamento e responsável são os dele). Só aparecem as entregas do setor.
- Cada setor vê apenas os próprios processos; só o administrador vê os de todos os setores.

### Controle do processo e automações
- **Requisitos por etapa:** em *Entregas → Configurar*, cada etapa pode exigir documentos anexados ou confirmações para o processo sair dela. O servidor bloqueia o avanço enquanto houver pendência (voltar de etapa não exige; ao reentrar, exige de novo).
- **Aprovação:** cada etapa define quem pode mover processos para ela (qualquer responsável, gestor ou só administrador). Quem não pode, solicita; gestor/administrador aprova (e o processo avança) ou recusa com motivo.
- **Painel:** aba com atrasados por setor, tempo médio por etapa e carga por funcionário. O administrador vê todos os setores; gestor e usuário veem só o próprio setor, e na carga por funcionário só pessoas desse setor (nunca administradores).
- **Automações** (ao entrar numa etapa ou ao cancelar): gerar a ficha do processo em PDF e chamar um webhook (https, endereço público, JSON assinado com HMAC-SHA256). Falha nunca desfaz o movimento e fica registrada no processo.
- Documentos em disco, sem S3, para desenvolvimento: `ARMAZENAMENTO=local`.

### Acesso e senhas
- Entra-se com **nome e senha**. O nome não diferencia maiúsculas de minúsculas e é único na empresa (não aceita `@`). Contas antigas ainda podem entrar com o e-mail.
- Não há cadastro aberto de empresas: o primeiro administrador nasce da configuração (`ADMIN_NOME`, `ADMIN_SENHA`, `ADMIN_EMPRESA`) quando o banco está vazio. Para reativar o cadastro aberto (uso em testes), defina `CADASTRO_EMPRESA_ABERTO=true`.
- Administrador cria usuários com nome, **e-mail (obrigatório)**, senha inicial, papel e setor. Gestor informa nome, e-mail, senha inicial e papel (Usuário); o funcionário já nasce no setor do gestor. O e-mail é único no sistema, e o administrador (qualquer pessoa) e o gestor (funcionários do próprio setor) podem **editá-lo** na aba Usuários; ele só aparece para administrador, gestor e para a própria pessoa. O acesso continua sendo por nome e senha.
- A senha inicial é **provisória**: no primeiro acesso a pessoa cria a própria senha, e o servidor bloqueia o resto do sistema até isso acontecer.
- O administrador pode **zerar a senha** de qualquer outra conta (funcionário, gestor ou administrador): ele informa na hora a nova senha, que também é provisória. A sessão aberta da pessoa é encerrada.
- O usuário é conferido a cada requisição: quem é desativado ou apagado perde o acesso imediatamente.
- **Bloqueio de conta:** depois de 5 erros de senha, o erro seguinte bloqueia a conta e a tela manda **entrar em contato com o suporte**. Na aba Usuários, o botão **Desbloquear** só aparece para quem está bloqueado (administrador desbloqueia qualquer pessoa; gestor, funcionários do próprio setor). Zerar a senha também desbloqueia.
- **Verificação em duas etapas (opcional):** em *Meu perfil*, com aplicativo autenticador (TOTP). O segredo é guardado cifrado. O administrador desliga a de quem perdeu o celular.
- **Meu perfil** também permite alterar a própria senha (informando a atual). **Sair** invalida o token no servidor.
- **Auditoria** (aba só do administrador): entradas, bloqueios, senhas, criação e alteração de usuários.
- **Cabeçalhos de segurança** no nginx (CSP, X-Frame-Options, nosniff…) e na API; chat de ajuda com limite de 20 mensagens a cada 10 minutos por pessoa.
- Limitações: sem recuperação de senha por e-mail (o suporte zera); o bloqueio é por conta, não por endereço de origem; o token fica no `localStorage` do navegador.

### Relatórios (PDF)
A aba **Relatórios** gera um PDF dos processos, com filtros por setor, responsável, empresa, período (abertura ou encerramento) e situação (em andamento, concluídos, cancelados). A abrangência depende do papel e é imposta pelo servidor:

| Administrador | Gestor | Usuário |
|---|---|---|
| todos os setores | só o próprio setor (sem setor definido, não gera) | só o próprio setor (sem os processos de funcionários apagados) |

A tela mostra uma prévia com os totais antes de gerar. Limite de 10.000 processos por relatório.

### Apagar cadastros
O botão **X** apaga empresas, setores, entregas, fluxos e funcionários (sempre com confirmação). Os processos nunca são apagados:
- **Só processo em andamento impede apagar.** Processo concluído ou cancelado não bloqueia; ele continua existindo, apenas sem o vínculo com o que foi apagado (aparece "—"; o título e o histórico permanecem).
- Apagar uma **entrega** tira ela da lista dos setores e apaga o fluxo dela; apagar um **setor** deixa seus funcionários sem setor.
- **Entrega com processos em andamento:** o X pede a **entrega substituta**. Os processos passam para ela (mantêm número, responsável e histórico), cada um para a etapa de mesmo nome no fluxo da substituta ou, se não houver, para a etapa inicial, com o prazo daquela etapa; pedidos de aprovação pendentes são cancelados e o histórico registra a troca. A substituta precisa ser do mesmo setor.
- **Fluxo:** não apaga se alguma entrega usa o fluxo nem se há processo em andamento nele. As etapas e transições são apagadas junto.
- **Funcionário:** ninguém apaga a si mesmo; o gestor só apaga os do próprio setor e, ao passar os processos, só escolhe herdeiros desse setor (administrador só é herdeiro quando quem apaga é administrador). Se ele tem processos, aparece um aviso ("Apagar mesmo assim" / "Desistir de apagar"). Os processos em andamento e atrasados passam para os herdeiros escolhidos, com um novo prazo; os concluídos e cancelados ficam com o nome dele ("(removido)") e só o gestor do setor e o administrador os veem. O histórico permanece (o autor aparece como "usuário removido").

### Papéis
Todos enxergam os cadastros e fluxos da empresa. **Processos e pessoas são por setor:** o administrador vê todos; gestor e usuário veem só os do próprio setor (na aba Usuários, nunca administradores). O que muda entre os papéis é quem altera.

| | Administrador | Gestor | Usuário |
|---|---|---|---|
| Empresas atendidas | cadastra, altera e apaga | cadastra, altera e apaga | só consulta |
| Setores | cadastra, altera e apaga | só consulta | só consulta |
| Entregas | cadastra em qualquer setor, altera e apaga todas | cadastra e mexe só nas do próprio setor | só consulta |
| Fluxo (etapas, requisitos e automações de cada entrega) | altera todos | altera o das entregas do próprio setor | só consulta |
| Funcionários | adiciona (qualquer papel), altera papel, setor e situação, apaga (menos a si mesmo) | adiciona (só papel Usuário), ativa/desativa e apaga os de papel Usuário; não altera papel nem setor | só consulta |
| Processos | mexe em qualquer um | mexe em qualquer um do próprio setor | abre processos; só mexe nos que é o responsável |

### Prazos
**Só a etapa de conclusão tem prazo** (`prazo_dias`, opcional): o prazo **padrão** da entrega, em dias. Ao abrir o processo, o campo **Prazo para concluir** aparece como data (**dd/mm/aaaa**), opcional: vazio vale o padrão da entrega; ou se escolhe outra data (não anterior a hoje). Esse prazo **não reinicia** a cada mudança de etapa; sem prazo, o processo não atrasa. Um job diário (`PRAZOS_CRON`, padrão 08:00 America/Sao_Paulo) avisa o gestor do setor sobre prazos vencendo/vencidos, **uma vez por processo**.

## Configuração (variáveis de ambiente)

Ver `.env.example` e `backend/src/main/resources/application.yml`. Obrigatórias em produção:

| Variável | Descrição |
|---|---|
| `JWT_SECRET` | ≥ 32 bytes. **Troque o valor padrão.** |
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | MySQL |
| `S3_BUCKET`, `S3_REGIAO` | Bucket real. Sem `S3_ENDPOINT`, usa a AWS (credenciais via IAM Role) |
| `CORS_ORIGINS` | Origens permitidas (não necessário se front e API ficam no mesmo domínio via nginx) |
| `VIRIATO_JWT_PERMITIR_PADRAO` | **Só em desenvolvimento local:** aceita o `JWT_SECRET` de exemplo. Sem isso, o sistema se recusa a subir com o segredo de exemplo. Rodando `mvn spring-boot:run` sem `JWT_SECRET`, defina `VIRIATO_JWT_PERMITIR_PADRAO=true` |
| `SWAGGER_ATIVO` | `true` liga a documentação interativa da API (padrão: desligada) |

## Produção (sugestão AWS)

ECS Fargate (backend + frontend) · RDS MySQL · S3 com bloqueio de acesso público e criptografia · Task Role do ECS com permissão só no bucket · HTTPS no ALB. As imagens são geradas pelos `Dockerfile` de `backend/` e `frontend/`.

## Roadmap

- [ ] Edição de fluxos existentes e transições não sequenciais na UI (a API já aceita transições arbitrárias)
- [ ] Recuperação de senha por e-mail e 2FA obrigatório para administradores
- [ ] Campos personalizados por fluxo (hoje: JSON livre via API)
- [ ] Cobrança/planos e limites por empresa
- [ ] Feriados e prazos em dias úteis
- [ ] Limite de tentativas por endereço de origem (hoje o bloqueio é por conta)
