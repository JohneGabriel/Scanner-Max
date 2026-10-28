# OBD ELM327

App Android simples para adaptadores ELM327 Bluetooth Clássico (SPP).

- Leitura ao vivo: RPM, velocidade, temperatura do motor e da admissão, carga, acelerador e bateria
- Leitura de códigos de falha confirmados (modo 03) e pendentes (modo 07)
- Apagar códigos de falha (modo 04), com confirmação
- Log dos comandos enviados e das respostas, para depurar

## Build

Cada push na `main` roda o workflow `.github/workflows/build.yml`, que gera o APK
e publica na release **latest** (aba Releases do repositório).

`app/debug.keystore` é uma chave de debug fixa, para que todo APK tenha a mesma
assinatura e possa ser instalado por cima do anterior. Não use essa chave para publicar na Play Store.
