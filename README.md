# Screeaner

Transforma um tablet Android em um **monitor secundário de verdade** para o macOS, com foco em **latência mínima**.

O Mac cria um display virtual do tamanho exato da tela do tablet (aparece em *Ajustes do Sistema › Telas*, dá para arrastar janelas para ele), captura só esse display, codifica por hardware e envia para o tablet por USB ou Wi‑Fi. O toque e a caneta do tablet voltam para o Mac como mouse.

```
 macOS                                                         Android
┌───────────────────────────────────────────────┐            ┌──────────────────────────────┐
│ CGVirtualDisplay ─► ScreenCaptureKit (NV12,   │            │ MediaCodec (low-latency) ──► │
│ zero-copy) ─► VideoToolbox H.264/HEVC         │ ── TCP ──► │ SurfaceView (render imediato)│
│ (low-latency RC, sem B-frames, bitrate        │  USB: TCP  │                              │
│ adaptativo)                                   │ Wi‑Fi: UDP │                              │
│ CGEvent (mouse/scroll/zoom/teclado) ◄─────────│ ◄── TCP ── │ toque · caneta · teclado     │
└───────────────────────────────────────────────┘            └──────────────────────────────┘
        USB: adb reverse (127.0.0.1)   ·   Wi‑Fi: descoberta automática via Bonjour
```

## Como usar

### 1. Mac (macOS 13+)

```bash
cd mac
./scripts/bundle.sh          # gera mac/build/Screeaner.app
open build/Screeaner.app
```

O app fica na barra de menus. Na primeira vez, conceda:

- **Gravação de Tela** — para capturar o display virtual;
- **Acessibilidade** — para o toque do tablet controlar o cursor (opcional).

> **Assinatura estável:** o macOS vincula essas permissões à assinatura do app. Rode uma vez `./scripts/create-signing-identity.sh` (cria o certificado local "Screeaner Local Signing", pede a senha do Mac) e o `bundle.sh` passa a usá-lo sozinho — as permissões continuam valendo entre um build e outro. Sem ele, a assinatura é ad-hoc e as permissões precisam ser concedidas de novo a cada build. Releases oficiais podem ser assinados com Developer ID e notarizados; veja [docs/DISTRIBUTION.md](docs/DISTRIBUTION.md).

No menu do Mac também dá para **abrir o Screeaner ao iniciar o Mac** e **abrir o app no tablet ao conectar o USB** (via `adb`).

### Pareamento e segurança

Na primeira conexão de cada tablet, **o Mac e o tablet mostram o mesmo código de 6 dígitos**. Confira se são iguais, toque em *O código é o mesmo* no tablet e clique em *Permitir* no Mac. A partir daí os dois se reconhecem sozinhos.

- Sem a aprovação no Mac, nenhum aparelho da rede consegue ver a tela ou controlar o Mac.
- Todo o tráfego é criptografado (AES‑256‑GCM): controle, toque, teclado, área de transferência, áudio e vídeo, inclusive por UDP.
- O código protege contra intermediários: ele é derivado das chaves dos dois lados, com um compromisso que impede um atacante de escolher chaves que gerem o mesmo número.
- No menu do Mac, **Tablets autorizados** lista os tablets pareados e permite remover cada um. No tablet, *Opções › Esquecer Macs* faz o inverso.

### 2. Tablet (Android 8+)

```bash
cd android
./gradlew installDebug       # ou abra a pasta no Android Studio
```

Cada push também gera o APK e o `.app` como artefatos do GitHub Actions (workflow **Build**). Versões publicadas ficam em **Releases**, e depois da primeira instalação o próprio Mac mantém o tablet atualizado (veja [Atualizações](#atualizações-sem-google-play-nem-app-store)).

### 3. Conectar

| Modo | O que fazer | Quando usar |
|---|---|---|
| **USB** (recomendado) | Ative a *Depuração USB* no tablet, conecte o cabo e abra o app. O Mac roda `adb reverse` sozinho (precisa de `brew install android-platform-tools`). | Menor latência e zero jitter |
| **Wi‑Fi** | Mac e tablet na mesma rede; o tablet encontra o Mac sozinho. Se a rede bloquear mDNS, digite o IP mostrado no menu do Mac. | Sem cabo; prefira 5 GHz / Wi‑Fi 6 |

### Gestos no tablet

| Gesto | Ação no Mac |
|---|---|
| Um dedo / caneta | Clique e arrasto (toques seguidos viram duplo clique) |
| Caneta | Funciona como **mesa digitalizadora**: pressão, inclinação e borracha chegam como eventos de tablet, que apps de desenho leem |
| Caneta pairando | Move o cursor (e avisa os apps que a caneta está perto da tela) |
| Mão apoiada na tela | Ignorada enquanto a caneta está em uso (rejeição de palma, pode ser desligada) |
| Arrastar com dois dedos | Rolagem |
| Pinça com dois dedos | Zoom (gesto de ampliação do trackpad) |
| Toque com dois dedos | Clique direito |
| Toque com três dedos | Alterna o monitor: linha compacta → painel completo → oculto |
| Girar o tablet | Alterna entre paisagem e retrato **sem reconectar**: o display muda de resolução no lugar |

**Teclado e mouse do tablet** — um teclado físico (USB ou Bluetooth) ligado ao tablet digita no Mac enquanto a transmissão está ativa, incluindo modificadores (a tecla Meta/Windows vira ⌘), atalhos e o teclado numérico. Teclados **ABNT2** funcionam com o layout *Brasileiro* do Mac, incluindo ç, acentos e as teclas extras `\ |` e `/ ?`. Em teclados de PC, a opção *Ctrl do teclado funciona como ⌘* deixa Ctrl+C/Ctrl+V como no Mac. Um mouse ou trackpad ligado ao tablet também controla o cursor, com botão direito e rolagem. Atalhos que o próprio Android intercepta (por exemplo Meta+Tab) não chegam ao Mac.

**Barra de atalhos** — em *Opções*, ative a barra lateral com botões do Mac: ⌘ ⌥ ⌃ ⇧ fixos (toque em ⌘ e depois clique = ⌘‑clique), desfazer, refazer, copiar, colar, Esc e outros. *Opções › Atalhos…* escolhe quais botões aparecem.

**Área de transferência** — texto copiado no Mac pode ser colado no tablet e vice‑versa (pode ser desligado no Mac e no tablet).

**Áudio** — em *Opções › Áudio do Mac no tablet*, o som do Mac também toca no tablet, com atraso mantido abaixo de ~80 ms. O Mac continua tocando o som; baixe o volume dele se quiser ouvir só no tablet.

**Modo de exibição** — escolhido no tablet, na tela de conexão ou no painel do monitor (três dedos). Trocar o modo reconecta em cerca de um segundo.

| Modo | O que acontece no Mac |
|---|---|
| **Extensão** (padrão) | O tablet vira mais uma área de trabalho. A posição pode ficar como o macOS lembra ou ser fixada à esquerda, à direita, acima ou abaixo da tela do Mac. |
| **Monitor principal** | Igual à extensão, mas o tablet passa a ser o display principal: a barra de menus, o Dock e as janelas novas vão para ele. A posição relativa à tela do Mac é mantida. |
| **Espelhar o Mac** | O tablet mostra a própria tela do Mac, com faixas pretas se o formato for diferente; o toque controla a tela do Mac. Nenhum display virtual é criado. Com mais de uma tela no Mac, o menu escolhe qual espelhar. |
| **Só o tablet** | O tablet vira o display principal e as telas do Mac passam a espelhá‑lo, então todas as janelas ficam no tablet; a tela embutida do Mac é apagada (brilho 0) para economizar bateria e é restaurada ao desconectar. |

As mudanças de arranjo valem só enquanto o app do Mac está aberto: ao sair, o macOS volta ao arranjo dele. Com vários tablets, o último a pedir "Monitor principal" fica com a barra de menus.

**Vários tablets** — basta abrir o app em cada um: cada tablet ganha o próprio display virtual, e o macOS lembra a posição de cada um separadamente.

## De onde vem a baixa latência

Cada etapa foi escolhida para não acumular fila:

1. **Display virtual nativo** — a resolução é a do painel do tablet (pixel a pixel, sem reescala) e o refresh segue o do tablet (até 120 Hz).
2. **Captura zero-copy** — o ScreenCaptureKit entrega `IOSurface` em NV12, que o VideoToolbox consome direto, e só quando a tela muda.
3. **Encoder em modo tempo real** — rate control de baixa latência, sem B-frames, sem lookahead, um quadro entra e um sai. Keyframes só sob demanda (são os maiores picos de latência), e nenhum quadro passa de 4× o tamanho médio: quadro grande demora para atravessar o enlace (500 KB levam ~40 ms num Wi‑Fi de 100 Mbps).
4. **Descartar antes de codificar** — se o socket está atrasado, o quadro capturado é pulado *antes* do encoder. Nunca se enfileira vídeo velho, e a cadeia de referência continua íntegra.
5. **Transporte certo para cada enlace** — por USB, TCP com `TCP_NODELAY` (enlace sem perdas). No Wi‑Fi, o vídeo vai por **UDP com correção de erros Reed‑Solomon**: cada bloco de até 32 pacotes leva pacotes de paridade e o tablet reconstrói *quaisquer* pacotes perdidos, até a quantidade de paridade, sem esperar retransmissão (que é o que causa travadas no TCP). Os blocos são intercalados no envio, então uma rajada de perdas se espalha por vários blocos. Se o UDP estiver bloqueado, o vídeo continua por TCP automaticamente.
6. **Bitrate adaptativo** — a cada segundo o tablet informa perda de pacotes, quadros perdidos e ping; o Mac reduz o bitrate rápido ao primeiro sinal de congestionamento e sobe devagar quando o enlace está limpo. A quantidade de paridade do FEC (10–50%) acompanha a perda medida.
7. **Refino em tela parada** — quando a imagem para de mudar, o último quadro é recodificado duas vezes: recupera a nitidez perdida durante o movimento e, no UDP, revela ao tablet se o último quadro se perdeu.
8. **Decoder low-latency** — `KEY_LOW_LATENCY`, prioridade realtime, chaves de fabricantes (Qualcomm, HiSilicon…) e renderização imediata no `SurfaceView`, sem esperar timestamp.
9. **Painel na taxa máxima** — o tablet é colocado no maior refresh disponível para reduzir o tempo de scan-out.
10. **Rádio Wi‑Fi sem economia de energia** — durante a transmissão o tablet segura um `WifiLock` em modo de baixa latência (Android 10+). Sem ele, o rádio cochila entre beacons e gera picos de 50–100 ms.
11. **Entrada sem espera** — toque, caneta, hover e mouse são entregues no instante em que chegam (`requestUnbufferedDispatch`), sem esperar o próximo vsync. Saem marcados com DSCP EF, que o Wi‑Fi coloca na fila de voz, à frente do tráfego comum. Com um dedo, o cursor vai para baixo do dedo na hora; só o clique espera alguns milissegundos para distinguir gestos de dois dedos.
12. **Threads de rede em prioridade de display** — leitura do vídeo, envio da entrada, decoder e UDP rodam como `URGENT_DISPLAY`, para não esperar atrás de trabalho em segundo plano.
13. **Sem lixo no caminho do vídeo** — o tablet reaproveita os buffers de remontagem dos quadros UDP. Alocar um por quadro gerava ~3 MB/s de lixo, e as coletas resultantes apareciam como picos de latência.

## Monitor de latência e desempenho (tablet)

Um toque com três dedos alterna entre uma **linha compacta**, o **painel completo** e oculto. O painel mostra, atualizado a cada segundo e com gráfico do último minuto:

- **Latência Mac→tela** — medida quadro a quadro: do instante em que o Mac começa a codificar o quadro até o tablet entregá-lo à tela, com média e pico. O tablet alinha o relógio com o do Mac pelo ping (a cada segundo), então o número tem margem de erro de até meio ping. Não inclui a captura no Mac nem o tempo de varredura do painel.
- **Parcelas** — `encode` (no Mac), `rede` (meia viagem do ping), `transf` (do primeiro byte ou datagrama do quadro até o quadro completo: o tempo de um quadro grande atravessar o enlace) e `decode` (da entrada no decoder até a tela, média e pico).
- **Encoder** — codec, fps e se o Mac conseguiu o modo de *baixa latência* do VideoToolbox (sem ele, o encoder segura cerca de um quadro) e o limite de tamanho por quadro.
- **Desempenho** — transporte, fps, bitrate, perda de pacotes, quadros perdidos, keyframes pedidos, uso de CPU do app (% de um núcleo), memória, bateria, temperatura e estado térmico do tablet.

### Teste toque→tela

No painel completo, **⏱ Medir toque→tela** mede a ida e volta real, sem câmera e sem depender do relógio do Mac:

1. o tablet pede ao Mac para pintar de preto ou de branco um quadrado no centro do display virtual e dispara o cronômetro;
2. o pedido viaja como um toque; o quadrado é uma janela comum, então a mudança passa pelo caminho inteiro (WindowServer → ScreenCaptureKit → encoder → rede → decoder);
3. o tablet copia (`PixelCopy`) o centro de cada quadro decodificado até ver a cor nova.

São 30 amostras com intervalos aleatórios (para cair em todas as fases do refresh). O resultado mostra média, p50, p95, mínima e máxima, e separa **ida tablet→Mac** de **volta Mac→tela** usando o relógio sincronizado pelo ping. Com uma sessão sendo gravada, o resultado também vai para o CSV e para o resumo.

O número inclui tudo entre o app do tablet e o vídeo decodificado, inclusive a composição e a captura no Mac, que o monitor quadro a quadro não vê. Não inclui o digitalizador do toque nem a varredura do painel. Antes de medir, o teste calibra quatro regiões do quadrado e ignora as que o cursor do Mac estiver cobrindo.

### Benchmark automático

**🏁 Benchmark automático** roda, com um toque, uma matriz de configurações e mostra qual tem a menor latência:

| Configuração | Para que serve |
|---|---|
| HEVC 60 · com e sem limite por quadro | mede o efeito do limite de tamanho por quadro |
| H.264 60 · com e sem limite por quadro | o mesmo, no codec que costuma ter modo de baixa latência |
| H.264 e HEVC na taxa alta do painel (90–120 Hz) | se o atraso do encoder é de um quadro inteiro, ele cai pela metade |

Cada configuração é uma transmissão nova, que o tablet pede ao Mac só para aquele teste (os ajustes do menu não mudam). Para cada uma: 3 s de aquecimento, 8 s com um **padrão de movimento fixo** cobrindo a tela do Mac (sempre o mesmo, para que rodadas sejam comparáveis) e 20 amostras do teste toque→tela com a tela parada. Leva cerca de 2 minutos; tocar de novo interrompe. No fim a transmissão normal volta sozinha.

O resultado aparece no painel (uma linha por configuração, com toque p50/p95, latência em movimento e as parcelas, e a melhor configuração) e vai para `screeaner-benchmark-….csv`, ao lado das sessões e em `Downloads/Screeaner/`. **Compartilhar** envia o arquivo mais recente, inclusive o do benchmark.

### Sessões de teste

No painel completo, **● Gravar sessão de teste** grava uma linha por segundo com todas as medidas acima. Ao parar, o arquivo ganha um resumo (latência média, p50, p95 e máxima, fps e bitrate médios, perdas, CPU média, temperatura máxima) e o cabeçalho registra as condições do teste: modelo do tablet, versões dos dois apps, resolução, codec e fps.

- O CSV fica em `Android/data/app.screeaner/files/sessions/` e, no Android 10+, também em `Downloads/Screeaner/`.
- **Compartilhar** envia a última sessão como texto por qualquer app (e-mail, Drive, mensagens).
- A linha compacta mostra `●` enquanto a gravação está ativa; ela continua mesmo se a conexão cair e voltar.

### Versão do app

A versão aparece no rodapé da tela de conexão e no título do painel do tablet (`Screeaner v0.7.0 (7) · Android … · modelo`), e no rodapé do menu do Mac. Cada lado também mostra a versão do outro: o menu do Mac lista a versão do app de cada tablet, e as sessões gravadas registram a versão do Mac.

## Atualizações (sem Google Play nem App Store)

Cada tag `vX.Y.Z` publica um **GitHub Release** (workflow **Release**) com:

- `Screeaner.apk` — o app do tablet, sempre assinado com a **mesma chave**, então instala por cima da versão anterior;
- `Screeaner-mac.zip` — o app do Mac, **com o APK do tablet dentro**.

**O Mac atualiza o tablet sozinho.** Quando um tablet com versão mais velha conecta:

- **por USB** (com `adb`): o Mac instala com `adb install -r` e reabre o app — nenhum toque;
- **por Wi‑Fi** (ou se o adb falhar): o tablet pergunta *"Atualizar o Screeaner para vX?"*; um toque, o APK vem pela própria conexão (conferido por tamanho e SHA‑256) e o Android pede a confirmação de instalação. Na primeira vez, o Android pede para permitir instalações vindas do Screeaner.

O Android só aceita a atualização se ela tiver a mesma assinatura do app instalado: um APK adulterado é recusado pelo próprio tablet.

Para o Mac, baixe o `Screeaner-mac.zip` do Release e substitua o app.

### Preparar uma vez

1. **Chave do app Android** — no Mac, crie o keystore e **guarde uma cópia em lugar seguro** (sem ele não dá para publicar atualizações que instalem por cima):

   ```bash
   keytool -genkeypair -v -keystore screeaner.jks -alias screeaner -keyalg RSA -keysize 4096 -validity 36500
   ```

   (Precisa de Java; sem ele, gere em qualquer máquina que tenha — o arquivo é o mesmo.) No GitHub, em *Settings › Secrets and variables › Actions*, crie `ANDROID_KEYSTORE_BASE64` (saída de `base64 -i screeaner.jks`) e `ANDROID_KEYSTORE_PASSWORD`. O alias padrão é `screeaner` e a senha da chave é a mesma do keystore; se forem outros, crie também `ANDROID_KEY_ALIAS` e `ANDROID_KEY_PASSWORD`.

2. **Certificado do Mac (opcional, grátis)** — sem conta Apple Developer, um certificado autoassinado basta para o macOS **manter as permissões** de Gravação de Tela e Acessibilidade entre versões: *Acesso às Chaves › Assistente de Certificado › Criar um Certificado…*, nome `Screeaner`, tipo de identidade *Raiz Autoassinada*, tipo de certificado *Assinatura de Código*. Exporte como `.p12` e crie os secrets `MAC_CERT_P12_BASE64` (`base64 -i Screeaner.p12`) e `MAC_CERT_PASSWORD`. Para builds locais: `CODESIGN_IDENTITY="Screeaner" ./scripts/bundle.sh`. Sem notarização, o macOS pede *clique direito › Abrir* na primeira execução de cada versão baixada.

3. **Transição** — os APKs anteriores ao primeiro Release eram assinados com chaves de debug diferentes: desinstale o Screeaner do tablet e instale o `Screeaner.apk` do Release uma vez. Daí em diante as atualizações são automáticas.

**Da 0.6 para a 0.7** — a 0.7 trouxe pareamento e criptografia, então os dois apps precisam estar na mesma geração. Basta atualizar o Mac: quando um tablet ainda na 0.6 conecta, o Mac 0.7 reconhece o app antigo e oferece a atualização pelo mesmo fluxo da 0.6 (adb por USB, ou um toque no tablet pelo Wi‑Fi). Um tablet 0.7 ligado a um Mac antigo avisa que o app do Mac precisa ser atualizado.

### Publicar uma versão

Suba `versionCode`/`versionName` em `android/app/build.gradle.kts` e `CFBundleShortVersionString`/`CFBundleVersion` em `mac/Resources/Info.plist`, e crie a tag (o workflow confere que ela bate com o `versionName`):

```bash
git tag v0.7.0 && git push origin v0.7.0
```

Notarização (opcional, exige conta Apple Developer): com um certificado *Developer ID Application* em `MAC_CERT_*` e os secrets `APPLE_ID`, `APPLE_TEAM_ID` e `APPLE_APP_PASSWORD`, o Release também notariza o app, e o macOS o abre sem avisos. O Release publica ainda `Screeaner-android.aab`, o pacote para a Play Store. Mais detalhes em [docs/DISTRIBUTION.md](docs/DISTRIBUTION.md).

## Ajustes (menu do Mac)

- **Resolução** — *Retina (HiDPI)*: nítido, área de trabalho com metade dos pontos; *Nativa 1x*: mais espaço, elementos menores.
- **Taxa de quadros** — 30/60/90/120 (limitada pelo refresh do tablet).
- **Qualidade máxima** — teto do bitrate, de 10 a 80 Mbps. Por USB, valores altos são seguros.
- **Bitrate adaptativo** — ajusta o bitrate abaixo do teto conforme a rede (ligado por padrão).
- **UDP com correção de erros no Wi‑Fi** — desligue para forçar TCP.
- **Codec** — H.264 (decode mais rápido na maioria dos tablets) ou HEVC (mais nítido no mesmo bitrate).
- **Abrir ao iniciar o Mac**, **abrir o app no tablet ao conectar o USB**, **compartilhar a área de transferência** e **tela espelhada**.
- **Tablets autorizados** — lista dos tablets pareados, com botão para remover.

Mudar um ajuste reinicia a transmissão automaticamente.

## Protocolo

**Canal de controle** — uma conexão TCP na porta `27183`. Toda mensagem é `[tipo: u8][tamanho: u32 BE][payload]`.

**Pareamento** (em claro, P‑256 ECDH, SHA‑256):

| Tipo | Sentido | Payload |
|---|---|---|
| 20 `SEC_HELLO` | tablet → Mac | `1` · chave estática 65 B · compromisso `H(efêmera ‖ nonce)` 32 B · id · nome |
| 21 `SEC_SERVER` | Mac → tablet | chave estática 65 B · chave efêmera 65 B · nonce 32 B · `flags` (bit 0 = Mac já conhece o tablet) · nome |
| 22 `SEC_REVEAL` | tablet → Mac | chave efêmera 65 B · nonce 32 B |
| 23 `SEC_CONFIRM` | tablet → Mac | `HMAC(kc, "tablet" ‖ T)` |
| 24 `SEC_OK` / 25 `SEC_DENIED` | Mac → tablet | `HMAC(kc, "mac" ‖ T)` / motivo |

`T` é o hash das três primeiras mensagens; as chaves saem de `HKDF(ECDH(efêmeras) ‖ ECDH(estáticas), sal = T)`. Se os dois já confiam na chave estática um do outro, o ECDH estático autentica a conexão; senão, o código de 6 dígitos (derivado de `T`) é comparado nas duas telas. Depois disso, toda mensagem vai como 26 `ENCRYPTED`: AES‑256‑GCM de `[tipo][payload]`, uma chave por sentido, nonce = contador da mensagem (reenvio ou reordenação derrubam a conexão).

**Mensagens (sempre dentro de `ENCRYPTED`)**:

| Tipo | Sentido | Payload |
|---|---|---|
| 1 `HELLO` | tablet → Mac | JSON: `width`, `height`, `densityDpi`, `refreshRate`, `codecs`, `name`, `udp`, `version`, `mode` (`extend`/`main`/`mirror`/`only`), `position` (`auto`/`left`/`right`/`above`/`below`), `clipboard`, `audio` e, opcional, `streamOverride` {`codec`, `fps`, `frameCap`} (benchmark) |
| 2 `CONFIG` | Mac → tablet | JSON: `codec`, `width`, `height`, `fps`, `token`, `udpPort`, `mode`, `server`, `version`, `audio`, `lowLatency`, `frameCap`. Um novo `CONFIG` no meio da sessão indica nova resolução |
| 3 `VIDEO` | Mac → tablet | `pts u64` · `flags u8` (1 = keyframe) · `encodeMicros u32` · access unit Annex‑B |
| 4 `TOUCH` | tablet → Mac | `action u8` (0 down, 1 move, 2 up, 3 hover, 4/5 caneta entra/sai do alcance) · `x f32` · `y f32` · `pressure f32` · `button u8` · `tiltX f32` · `tiltY f32` · `tool u8` (0 dedo/mouse, 1 caneta, 2 borracha) |
| 5 `SCROLL` | tablet → Mac | `dx f32` · `dy f32` · `x f32` · `y f32` |
| 6 `KEYFRAME_REQUEST` | tablet → Mac | vazio |
| 7 `PING` / 8 `PONG` | tablet ⇄ Mac | payload opaco, devolvido igual; o `PONG` acrescenta `macClockMicros u64` (o mesmo relógio do `pts`) |
| 9 `STATS` | tablet → Mac | `rttMicros u32` · `packetsExpected u32` · `packetsLost u32` · `framesLost u16` · `framesRendered u16` |
| 10 `ZOOM` | tablet → Mac | `phase u8` (0 início, 1 mudança, 2 fim) · `delta f32` · `x f32` · `y f32` |
| 11 `KEY` | tablet → Mac | `action u8` (0 down, 1 up) · `androidKeyCode u16` · `flags u8` (1 = repetição) · `scanCode u16` |
| 12 `PROBE` | tablet → Mac | `state u8` (0 oculto, 1 preto, 2 branco, 3/4 liga/desliga o padrão de movimento do benchmark) · `seq u32` |
| 13 `PROBE_ACK` | Mac → tablet | `seq u32` · `macClockMicros u64` (instante em que o quadrado foi pintado) |
| 14 `UPDATE_OFFER` | Mac → tablet | JSON: `versionName`, `versionCode`, `size`, `sha256` — o Mac traz um app do tablet mais novo |
| 15 `UPDATE_REQUEST` | tablet → Mac | vazio: o usuário aceitou |
| 16 `UPDATE_APK` | Mac → tablet | os bytes do APK |
| 17 `RESIZE` | tablet → Mac | JSON `width`, `height` (rotação) |
| 18 `CLIPBOARD` | tablet ⇄ Mac | texto UTF‑8 |
| 19 `AUDIO` | Mac → tablet | PCM s16le estéreo 48 kHz |

Coordenadas são normalizadas (0–1) em relação à tela do tablet.

**Compatibilidade com a 0.6** — um tablet antigo manda `HELLO` em claro. O Mac 0.7 não transmite para ele, mas aceita só a troca `UPDATE_OFFER` / `UPDATE_REQUEST` / `UPDATE_APK` em claro, para que o tablet se atualize (o Android confere a assinatura do APK).

**Vídeo por UDP** — quando o `CONFIG` traz `token ≠ 0`, o tablet envia para `udpPort` um datagrama `"SCRN"` + `token u32` (repetido como keep-alive) e o Mac responde nesse mesmo fluxo, a partir do próximo keyframe. Cada quadro (`pts u64` · `encodeMicros u32` · Annex‑B) é cifrado com AES‑256‑GCM (nonce = `frameId`) e só então protegido pelo FEC. Cada datagrama é:

`[frameId u32][index u16][k u16][m u16][flags u8][versão u8 = 2][frameSize u32][shard]`

O quadro cifrado vira `k` shards iguais de até 1200 bytes, agrupados em blocos de até 32, e cada bloco ganha shards de paridade Reed‑Solomon (matriz de Cauchy sobre GF(256)). Um bloco é reconstruído assim que recebe tantos shards quanto tem de dados, quaisquer que sejam. Um quadro irrecuperável faz o tablet pedir um keyframe. O formato é verificado nos dois lados pelo mesmo vetor de referência, e a criptografia por vetores calculados de forma independente em Python.

## Visual (Screeaner Dot)

Os dois apps seguem a mesma linguagem: **tinta clara sobre preto**, um único **vermelho de sinal** (`#E8352B`) só para o que está ao vivo, gravando ou errado, e **pontos como unidade de medida** (progresso em pontos, gráfico de latência em pontos, radar de busca). Rótulos numerados (`01 / CONEXÃO`) e linhas de 1 px no lugar de caixas e gradientes.

| Papel | Fonte |
|---|---|
| Números grandes e marca | Doto (matriz de pontos) |
| Interface | Space Grotesk |
| Rótulos e métricas | JetBrains Mono |

As fontes vão embutidas nos apps (tablet: `res/font`; Mac: `Contents/Resources/Fonts`), sob a SIL Open Font License — textos em `licenses/`.

## Estrutura

```
mac/      App de barra de menus (Swift Package): display virtual, captura, encoder, servidor, injeção de input
          Sources/ScreeanerCore: Reed‑Solomon, canal seguro e controle de bitrate, com testes (swift test)
android/  App Kotlin sem dependências externas: pareamento, decoder, FEC, áudio, caneta, teclado e atalhos
          ReedSolomon/FecAssembler/SecureChannel em Java, com testes JUnit
licenses/   Licenças das fontes (SIL OFL)
packaging/  Cask do Homebrew
docs/       Distribuição: assinatura, notarização, releases, lojas
```

## Limitações conhecidas

- `CGVirtualDisplay` é uma API privada do CoreGraphics (a mesma usada por apps como BetterDisplay): funciona fora da Mac App Store e pode mudar entre versões do macOS.
- No modo espelhar, o enquadramento com faixas pretas depende do ScreenCaptureKit preservar a proporção; isso é garantido a partir do macOS 14.
- O zoom por pinça usa um evento de gesto não documentado do macOS; pode não funcionar em todos os apps.
- A caneta como mesa digitalizadora usa os eventos de tablet do macOS; apps que exigem um driver específico (ex.: Wacom) podem tratá‑la só como mouse.
- O modo "Só o tablet" apaga a tela embutida pela API privada DisplayServices; se ela não existir na sua versão do macOS, a tela só deixa de ter janelas, sem apagar.
- O áudio vai pelo canal de controle (TCP); no Wi‑Fi, uma perda de pacote pode causar um pequeno corte.
- A sincronização da área de transferência é só de texto.
