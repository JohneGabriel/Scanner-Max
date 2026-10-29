# Vendas e licenças do Scanner Max

## Como funciona
1. **Nova venda** (repositório `scannermax-licencas` → Actions → Nova venda → Run workflow).
   Gera uma licença assinada e abre uma *issue* com a mensagem pronta para colar no chat do Mercado Livre.
2. O cliente abre o **link pessoal** (`https://johnegabriel.github.io/scannermax/a/#SMX1...`), baixa o app,
   instala e abre. O app pega a licença sozinho (área de transferência) ou pelo botão "Ativar no app".
3. O app pede a ativação ao repositório `scannermax-ativacao`. O workflow confere a assinatura, registra o
   aparelho (até 3 por licença) e devolve um recibo assinado. O cliente usa o app normalmente desde a
   primeira abertura; tem 3 dias para a ativação concluir (sem internet, por exemplo).
4. **Publicar versão** (repositório `Scanner-Max` → Actions): manda o APK para o site; os apps avisam o cliente.

## Repositórios
| Repositório | Visibilidade | Conteúdo |
|---|---|---|
| `Scanner-Max` | privado | código do app (este) |
| `scannermax` | **público** | site (GitHub Pages), APK e `versao.json` — arquivos de `servidor/repos/scannermax` |
| `scannermax-ativacao` | privado | workflow de ativação + respostas (`l/`) — arquivos de `servidor/repos/scannermax-ativacao` |
| `scannermax-licencas` | privado | registro de vendas, ferramentas e workflows do vendedor — `servidor/repos/scannermax-licencas` |

## Tokens (github.com → Settings → Developer settings → Fine-grained tokens)
- **Token do app** — repositório: só `scannermax-ativacao`; permissão: *Contents: Read and write*. Vai embutido no app.
- **Token de automações** — repositórios: `scannermax`, `scannermax-ativacao`, `scannermax-licencas`; permissão: *Contents: Read and write*.

Escolha a maior validade possível e anote a data: antes de vencer, gere outro, troque o segredo e publique uma versão.

## Segredos (Settings → Secrets and variables → Actions → New repository secret)
| Repositório | Segredo | Valor |
|---|---|---|
| Scanner-Max | `SMX_KEYSTORE_B64` | conteúdo de `scannermax-release.jks.b64` |
| Scanner-Max | `SMX_KEYSTORE_PASS` | senha da chave |
| Scanner-Max | `SMX_ACT_TOKEN` | token do app |
| Scanner-Max | `SMX_AUTOMACOES` | token de automações |
| scannermax-ativacao | `LICENSE_KEY` | conteúdo de `licenca-privada.pem` |
| scannermax-ativacao | `SMX_AUTOMACOES` | token de automações |
| scannermax-licencas | `LICENSE_KEY` | conteúdo de `licenca-privada.pem` |

## Guarde em lugar seguro (fora do GitHub também)
- `scannermax-release.jks` + senha: sem ela não dá para atualizar o app instalado nos clientes.
- `licenca-privada.pem`: quem tiver este arquivo consegue gerar licenças.
