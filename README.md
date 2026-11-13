# Screeaner

Transforma um tablet Android em um **monitor secundário de verdade** para o macOS, com foco em **latência mínima**.

O Mac cria um display virtual do tamanho exato da tela do tablet (aparece em *Ajustes do Sistema › Telas*, dá para arrastar janelas para ele), captura só esse display, codifica por hardware e envia para o tablet por USB ou Wi‑Fi. O toque e a caneta do tablet voltam para o Mac como mouse.

```
 macOS                                                         Android
┌───────────────────────────────────────────────┐            ┌──────────────────────────────┐
│ CGVirtualDisplay ─► ScreenCaptureKit (NV12,   │            │ MediaCodec (low-latency) ──► │
│ zero-copy) ─► VideoToolbox H.264/HEVC         │ ── TCP ──► │ SurfaceView (render imediato)│
│ (low-latency RC, sem B-frames)                │   NODELAY  │                              │
│ CGEvent (mouse/scroll) ◄──────────────────────│ ◄───────── │ toque · caneta · 2 dedos     │
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

> A assinatura é ad-hoc: o macOS vincula essas permissões à assinatura, então é preciso concedê-las de novo a cada rebuild. Para evitar isso, assine com um certificado estável: `CODESIGN_IDENTITY="Apple Development: …" ./scripts/bundle.sh`.

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
| Um dedo / caneta | Clique e arrasto (toques seguidos viram duplo clique; a pressão da caneta é enviada) |
| Caneta pairando | Move o cursor |
| Arrastar com dois dedos | Rolagem |
| Toque com dois dedos | Clique direito |
| Toque com três dedos | Mostra/oculta o painel de latência |

## De onde vem a baixa latência

Cada etapa foi escolhida para não acumular fila:

1. **Display virtual nativo** — a resolução é a do painel do tablet (pixel a pixel, sem reescala) e o refresh segue o do tablet (até 120 Hz).
2. **Captura zero-copy** — o ScreenCaptureKit entrega `IOSurface` em NV12, que o VideoToolbox consome direto, e só quando a tela muda.
3. **Encoder em modo tempo real** — rate control de baixa latência, sem B-frames, sem lookahead, um quadro entra e um sai. Keyframes só sob demanda (são os maiores picos de latência).
4. **Descartar antes de codificar** — se o socket está atrasado, o quadro capturado é pulado *antes* do encoder. Nunca se enfileira vídeo velho, e a cadeia de referência continua íntegra.
5. **Transporte simples** — TCP com `TCP_NODELAY`; por USB o enlace é praticamente sem perdas.
6. **Decoder low-latency** — `KEY_LOW_LATENCY`, prioridade realtime, chaves de fabricantes (Qualcomm, HiSilicon…) e renderização imediata no `SurfaceView`, sem esperar timestamp.
7. **Painel na taxa máxima** — o tablet é colocado no maior refresh disponível para reduzir o tempo de scan-out.

O painel de estatísticas (toque com três dedos) mostra cada parcela: `enc` (encode no Mac), `rede` (meia viagem do ping) e `dec` (da entrada no decoder até a tela).

## Ajustes (menu do Mac)

- **Resolução** — *Retina (HiDPI)*: nítido, área de trabalho com metade dos pontos; *Nativa 1x*: mais espaço, elementos menores.
- **Taxa de quadros** — 30/60/90/120 (limitada pelo refresh do tablet).
- **Qualidade** — bitrate de 10 a 80 Mbps. Por USB, valores altos são seguros.
- **Codec** — H.264 (decode mais rápido na maioria dos tablets) ou HEVC (mais nítido no mesmo bitrate).

Mudar um ajuste reinicia a transmissão automaticamente.

## Protocolo

Uma conexão TCP na porta `27183`. Toda mensagem é `[tipo: u8][tamanho: u32 BE][payload]`:

| Tipo | Sentido | Payload |
|---|---|---|
| 1 `HELLO` | tablet → Mac | JSON: `width`, `height`, `densityDpi`, `refreshRate`, `codecs`, `name` |
| 2 `CONFIG` | Mac → tablet | JSON: `codec`, `width`, `height`, `fps` |
| 3 `VIDEO` | Mac → tablet | `pts u64` · `flags u8` (1 = keyframe) · `encodeMicros u32` · access unit Annex‑B |
| 4 `TOUCH` | tablet → Mac | `action u8` (0 down, 1 move, 2 up, 3 hover) · `x f32` · `y f32` · `pressure f32` · `button u8` |
| 5 `SCROLL` | tablet → Mac | `dx f32` · `dy f32` · `x f32` · `y f32` |
| 6 `KEYFRAME_REQUEST` | tablet → Mac | vazio |
| 7 `PING` / 8 `PONG` | tablet ⇄ Mac | payload opaco, devolvido igual |

Coordenadas são normalizadas (0–1) em relação à tela do tablet.

## Estrutura

```
mac/      App de barra de menus (Swift Package): display virtual, captura, encoder, servidor, injeção de input
android/  App Kotlin sem dependências externas: descoberta, decoder, toque
```

## Limitações conhecidas

- `CGVirtualDisplay` é uma API privada do CoreGraphics (a mesma usada por apps como BetterDisplay): funciona fora da Mac App Store e pode mudar entre versões do macOS.
- Apenas um tablet por vez, em modo paisagem.
- A conexão não é criptografada — use em redes confiáveis ou por USB.
- Sem áudio.
