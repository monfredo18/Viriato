# Agente do Viriato

Programa pequeno, instalado no computador de cada usuário, que **lê os certificados digitais instalados nele** (repositório "Pessoal" do
Windows, inclusive A3 em token ou cartão) e conversa com o Viriato. O Viriato **não recebe arquivo `.pfx`, senha nem chave privada**:
só os dados públicos de cada certificado (titular, CNPJ, emissora, série, validade). Quando o sistema precisa usar o certificado (hoje:
consulta de notas na SEFAZ), pede ao agente, que faz a chamada com TLS mútuo **no próprio computador** e devolve só a resposta.

```
Navegador ──► Viriato (servidor ou Docker) ◄── agente (um por computador, sempre ele que chama o Viriato)
                                                   └─ repositório de certificados do Windows
```

## Instalar (Windows)

1. No Viriato, abra **Certificados e agente** e clique em **Ligar este computador**: aparece um código (vale 10 min, uso único).
2. No computador com os certificados, com **Java 21+** instalado, rode no PowerShell (não precisa de administrador):

   ```powershell
   .\instalar.ps1 -Servidor https://viriato.suaempresa.com.br -Codigo ABCD-2345
   ```

   Isso copia o programa para `%LOCALAPPDATA%\ViriatoAgente`, liga o computador à sua conta e cria uma tarefa que inicia o agente quando você entra no Windows.
3. Em poucos segundos o computador aparece em **Certificados e agente**, com os certificados que ele enxerga. As empresas são reconhecidas pelo CNPJ do titular.

Para conferir o que o agente enxerga: `java -jar viriato-agente.jar certificados`. Para remover: `desinstalar.ps1` (e **Desligar** na tela do Viriato).

## Gerar o programa

```bash
cd agente && mvn package        # gera target/viriato-agente.jar (Java 21, sem dependências)
```

## Segurança

- A ligação é sempre iniciada pelo agente (HTTPS, a cada ~5 s); o Viriato não precisa enxergar o computador.
- O token do agente é entregue uma única vez no pareamento e fica em `%APPDATA%\ViriatoAgente\agente.json`. No Viriato só existe o hash. Quem desliga o agente, ou sai da empresa, deixa-o sem acesso na hora.
- O agente só chama endereços **HTTPS de domínios permitidos** (padrão `fazenda.gov.br` e `gov.br`, porta 443); um pedido do servidor para outro endereço é recusado e registrado. Não segue redirecionamentos.
- A chave privada nunca é exportada: o Java pede ao Windows (ou ao token) para assinar a negociação TLS. Em A3 o Windows/driver pede o PIN, então o computador precisa estar com o token conectado e alguém presente.
- Confia nas autoridades do Windows (inclui a cadeia ICP-Brasil instalada nele); `truststore` no `agente.json` troca isso.
- Fora do Windows o agente não lê certificados (só roda em modo de teste, com `VIRIATO_AGENTE_PFX`/`VIRIATO_AGENTE_PFX_SENHA`).

## Limitações conhecidas

- A leitura do repositório do Windows (`Windows-MY`, SunMSCAPI) e a assinatura por token A3 **não foram exercitadas neste projeto** (o ambiente de desenvolvimento é Linux). O restante — protocolo com o Viriato, escolha do certificado e TLS mútuo — tem testes automatizados com certificados e servidor HTTPS reais (`mvn test`).
- Se o computador estiver desligado ou o agente fechado, a consulta à SEFAZ dessa empresa não roda e o Viriato diz isso na tela.
