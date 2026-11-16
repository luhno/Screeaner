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

Cada push também gera o APK e o `.app` como artefatos do GitHub Actions (workflow **Build**).

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
3. **Encoder em modo tempo real** — rate control de baixa latência, sem B-frames, sem lookahead, um quadro entra e um sai. Keyframes só sob demanda (são os maiores picos de latência).
4. **Descartar antes de codificar** — se o socket está atrasado, o quadro capturado é pulado *antes* do encoder. Nunca se enfileira vídeo velho, e a cadeia de referência continua íntegra.
5. **Transporte certo para cada enlace** — por USB, TCP com `TCP_NODELAY` (enlace sem perdas). No Wi‑Fi, o vídeo vai por **UDP com correção de erros Reed‑Solomon**: cada bloco de até 32 pacotes leva pacotes de paridade e o tablet reconstrói *quaisquer* pacotes perdidos, até a quantidade de paridade, sem esperar retransmissão (que é o que causa travadas no TCP). Os blocos são intercalados no envio, então uma rajada de perdas se espalha por vários blocos. Se o UDP estiver bloqueado, o vídeo continua por TCP automaticamente.
6. **Bitrate adaptativo** — a cada segundo o tablet informa perda de pacotes, quadros perdidos e ping; o Mac reduz o bitrate rápido ao primeiro sinal de congestionamento e sobe devagar quando o enlace está limpo. A quantidade de paridade do FEC (10–50%) acompanha a perda medida.
7. **Refino em tela parada** — quando a imagem para de mudar, o último quadro é recodificado duas vezes: recupera a nitidez perdida durante o movimento e, no UDP, revela ao tablet se o último quadro se perdeu.
8. **Decoder low-latency** — `KEY_LOW_LATENCY`, prioridade realtime, chaves de fabricantes (Qualcomm, HiSilicon…) e renderização imediata no `SurfaceView`, sem esperar timestamp.
9. **Painel na taxa máxima** — o tablet é colocado no maior refresh disponível para reduzir o tempo de scan-out.

## Monitor de latência e desempenho (tablet)

Um toque com três dedos alterna entre uma **linha compacta**, o **painel completo** e oculto. O painel mostra, atualizado a cada segundo e com gráfico do último minuto:

- **Latência Mac→tela** — medida quadro a quadro: do instante em que o Mac começa a codificar o quadro até o tablet entregá-lo à tela, com média e pico. O tablet alinha o relógio com o do Mac pelo ping (a cada segundo), então o número tem margem de erro de até meio ping. Não inclui a captura no Mac nem o tempo de varredura do painel.
- **Parcelas** — `encode` (no Mac), `rede` (meia viagem do ping) e `decode` (da entrada no decoder até a tela, média e pico).
- **Desempenho** — transporte, fps, bitrate, perda de pacotes, quadros perdidos, keyframes pedidos, uso de CPU do app (% de um núcleo), memória, bateria, temperatura e estado térmico do tablet.

### Latência de ponta a ponta

No painel, **Medir ponta a ponta** faz 20 medições do ciclo completo: o tablet pede ao Mac para trocar a cor de um pequeno quadrado no canto da tela e cronometra até ver a troca na própria tela. Isso inclui rede (ida e volta), o desenho no Mac, captura, codificação, decodificação e composição — só não inclui a varredura do painel. O resultado mostra média, mínimo e p95, e entra na sessão gravada se houver uma. Cada amostra fica alguns milissegundos acima do real, o tempo de ler a tela de volta.

### Sessões de teste

No painel completo, **● Gravar sessão de teste** grava uma linha por segundo com todas as medidas acima. Ao parar, o arquivo ganha um resumo (latência média, p50, p95 e máxima, fps e bitrate médios, perdas, CPU média, temperatura máxima) e o cabeçalho registra as condições do teste: modelo do tablet, versões dos dois apps, resolução, codec e fps.

- O CSV fica em `Android/data/app.screeaner/files/sessions/` e, no Android 10+, também em `Downloads/Screeaner/`.
- **Compartilhar** envia a última sessão como texto por qualquer app (e-mail, Drive, mensagens).
- A linha compacta mostra `●` enquanto a gravação está ativa; ela continua mesmo se a conexão cair e voltar.

### Versão do app

A versão aparece no rodapé da tela de conexão e no título do painel do tablet (`Screeaner v0.3.0 (3) · Android … · modelo`), e no rodapé do menu do Mac. Cada lado também mostra a versão do outro: o menu do Mac lista a versão do app de cada tablet, e as sessões gravadas registram a versão do Mac.

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
| 1 `HELLO` | tablet → Mac | JSON: `width`, `height`, `densityDpi`, `refreshRate`, `codecs`, `name`, `udp`, `version`, `mode` (`extend`/`main`/`mirror`/`only`), `position` (`auto`/`left`/`right`/`above`/`below`), `clipboard`, `audio` |
| 2 `CONFIG` | Mac → tablet | JSON: `codec`, `width`, `height`, `fps`, `token`, `udpPort`, `mode`, `server`, `version`, `audio`. Um novo `CONFIG` no meio da sessão indica nova resolução |
| 3 `VIDEO` | Mac → tablet | `pts u64` · `flags u8` (1 = keyframe) · `encodeMicros u32` · access unit Annex‑B |
| 4 `TOUCH` | tablet → Mac | `action u8` (0 down, 1 move, 2 up, 3 hover, 4/5 caneta entra/sai do alcance) · `x f32` · `y f32` · `pressure f32` · `button u8` · `tiltX f32` · `tiltY f32` · `tool u8` (0 dedo/mouse, 1 caneta, 2 borracha) |
| 5 `SCROLL` | tablet → Mac | `dx f32` · `dy f32` · `x f32` · `y f32` |
| 6 `KEYFRAME_REQUEST` | tablet → Mac | vazio |
| 7 `PING` / 8 `PONG` | tablet ⇄ Mac | payload opaco, devolvido igual; o `PONG` acrescenta `macClockMicros u64` (o mesmo relógio do `pts`) |
| 9 `STATS` | tablet → Mac | `rttMicros u32` · `packetsExpected u32` · `packetsLost u32` · `framesLost u16` · `framesRendered u16` |
| 10 `ZOOM` | tablet → Mac | `phase u8` (0 início, 1 mudança, 2 fim) · `delta f32` · `x f32` · `y f32` |
| 11 `KEY` | tablet → Mac | `action u8` (0 down, 1 up) · `androidKeyCode u16` · `flags u8` (1 = repetição) · `scanCode u16` |
| 12 `RESIZE` | tablet → Mac | JSON `width`, `height` (rotação) |
| 13 `CLIPBOARD` | tablet ⇄ Mac | texto UTF‑8 |
| 14 `AUDIO` | Mac → tablet | PCM s16le estéreo 48 kHz |
| 15 `PROBE` / 16 `PROBE_INFO` | tablet → Mac / Mac → tablet | `id u32` (0 mostra o marcador) / retângulo do marcador `x, y, w, h f32` |

Coordenadas são normalizadas (0–1) em relação à tela do tablet.

**Vídeo por UDP** — quando o `CONFIG` traz `token ≠ 0`, o tablet envia para `udpPort` um datagrama `"SCRN"` + `token u32` (repetido como keep-alive) e o Mac responde nesse mesmo fluxo, a partir do próximo keyframe. Cada quadro (`pts u64` · `encodeMicros u32` · Annex‑B) é cifrado com AES‑256‑GCM (nonce = `frameId`) e só então protegido pelo FEC. Cada datagrama é:

`[frameId u32][index u16][k u16][m u16][flags u8][versão u8 = 2][frameSize u32][shard]`

O quadro cifrado vira `k` shards iguais de até 1200 bytes, agrupados em blocos de até 32, e cada bloco ganha shards de paridade Reed‑Solomon (matriz de Cauchy sobre GF(256)). Um bloco é reconstruído assim que recebe tantos shards quanto tem de dados, quaisquer que sejam. Um quadro irrecuperável faz o tablet pedir um keyframe. O formato é verificado nos dois lados pelo mesmo vetor de referência, e a criptografia por vetores calculados de forma independente em Python.

## Estrutura

```
mac/      App de barra de menus (Swift Package): display virtual, captura, encoder, servidor, injeção de input
          Sources/ScreeanerCore: Reed‑Solomon, canal seguro e controle de bitrate, com testes (swift test)
android/  App Kotlin sem dependências externas: pareamento, decoder, FEC, áudio, caneta, teclado e atalhos
          ReedSolomon/FecAssembler/SecureChannel em Java, com testes JUnit
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
