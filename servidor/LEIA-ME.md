# Publicação do Scanner Max

Sem licença: quem tem o link instala e usa. A atualização chega pelo próprio app.

## Como funciona
- O APK fica num link fixo e público:
  `https://github.com/JohneGabriel/scannermax/releases/download/app/ScannerMax.apk`
- O app confere `versao.json` (a cada 12 h, e na tela Atualizações) e, se houver versão nova, baixa e entrega ao instalador do Android.
- **Publicar versão** (repositório `Scanner-Max` → Actions → Publicar versão → Run workflow): compila, assina com a chave definitiva, troca o APK do link e atualiza o `versao.json`. Antes de publicar, aumente `versionCode`/`versionName` em `app/build.gradle.kts`.

## Mensagem para o comprador
> Olá! Obrigado pela compra do Scanner Max 🚗🔧
> Baixe o app pelo link: https://github.com/JohneGabriel/scannermax/releases/download/app/ScannerMax.apk
> Ao abrir o arquivo, se o Android pedir, permita instalar apps desta fonte. As atualizações chegam pelo próprio app.
> Qualquer dúvida, é só chamar por aqui!

## Configuração (uma vez)
1. Crie o repositório **público** `scannermax` e envie os arquivos de `servidor/repos/scannermax` (site + `versao.json`).
   Em Settings → Pages, publique a partir da branch `main` (pasta raiz) para o site ficar em `https://johnegabriel.github.io/scannermax/`.
2. Crie um **token** (github.com → Settings → Developer settings → Fine-grained tokens): repositório só `scannermax`, permissão *Contents: Read and write*, maior validade possível.
3. No repositório `Scanner-Max` → Settings → Secrets and variables → Actions, crie:
   | Segredo | Valor |
   |---|---|
   | `SMX_KEYSTORE_B64` | conteúdo de `scannermax-release.jks.b64` |
   | `SMX_KEYSTORE_PASS` | conteúdo de `senha.txt` |
   | `SMX_AUTOMACOES` | o token do passo 2 |

## Guarde em lugar seguro (fora do GitHub)
`scannermax-release.jks` + senha. Sem eles, não dá para atualizar o app que já está instalado nos clientes.
