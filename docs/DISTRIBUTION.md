# Distribuição

Como assinar, empacotar e publicar o Screeaner no Mac e no Android.

## Mac: assinatura local estável

O macOS vincula as permissões de **Gravação de Tela** e **Acessibilidade** à assinatura do app. Com assinatura ad-hoc (o padrão), cada rebuild gera uma assinatura nova e as permissões precisam ser concedidas de novo. Para evitar isso, crie uma identidade de assinatura local uma única vez:

```bash
cd mac
./scripts/create-signing-identity.sh
```

O script:

1. gera um certificado autoassinado **"Screeaner Local Signing"** (uso de chave *digitalSignature*, uso estendido *codeSigning*) com `openssl`;
2. importa a chave e o certificado no chaveiro de login, liberando o acesso para `/usr/bin/codesign`;
3. marca o certificado como confiável para assinatura de código (`security add-trusted-cert -p codeSign`) — o macOS pede sua senha nesse passo.

Rodar de novo não faz nada se a identidade já existir.

Depois disso, `./scripts/bundle.sh` escolhe a identidade sozinho, nesta ordem:

| Prioridade | Identidade | Quando |
|---|---|---|
| 1 | `$CODESIGN_IDENTITY` | Variável definida (ex.: `Developer ID Application: …` no workflow de release) |
| 2 | `Screeaner Local Signing` | Aparece em `security find-identity -v -p codesigning` |
| 3 | ad-hoc (`-`) | Nenhuma das anteriores (é o caso do CI) |

O script imprime qual identidade usou. Com uma identidade `Developer ID Application`, ele assina com *hardened runtime* e carimbo de tempo (`--options runtime --timestamp`), exigidos pela notarização. O identificador de assinatura é sempre `app.screeaner.mac`.

> Na primeira vez com a nova assinatura, conceda as permissões mais uma vez (se o Screeaner já aparecer na lista de Ajustes do Sistema, remova-o e adicione de novo). Daí em diante elas sobrevivem aos rebuilds.

O ícone (`mac/Resources/icon.svg`) já está renderizado em `mac/Resources/AppIcon.iconset/`; o `bundle.sh` o compila para `AppIcon.icns` com `iconutil`.

## Publicar uma release

1. Atualize a versão nos dois apps (`mac/Resources/Info.plist`: `CFBundleShortVersionString`/`CFBundleVersion`; `android/app/build.gradle.kts`: `versionName`/`versionCode`) e o changelog em `android/fastlane/metadata/android/*/changelogs/<versionCode>.txt`.
2. Crie e envie a tag:
   ```bash
   git tag v0.4.0
   git push origin v0.4.0
   ```
3. O workflow **Release** (`.github/workflows/release.yml`) gera:
   - `Screeaner-mac.zip` — o `.app` (assinado com Developer ID e notarizado, se os secrets existirem; senão ad-hoc);
   - `Screeaner-android.apk` — APK de release (assinado com a chave de release, ou com a chave de debug se não houver keystore);
   - `Screeaner-android.aab` — Android App Bundle para a Play Store;

   e anexa os três a uma GitHub Release com o nome da tag.

O workflow também pode ser disparado manualmente (*Actions › Release › Run workflow*); nesse caso os arquivos ficam só como artefatos do run, sem criar release.

## Secrets do GitHub

Configure em *Settings › Secrets and variables › Actions*. Sem eles o workflow continua funcionando, só sem assinatura de distribuição.

**macOS (assinatura Developer ID + notarização)** — todos os cinco são necessários:

| Secret | Conteúdo |
|---|---|
| `MACOS_CERT_P12_BASE64` | Certificado *Developer ID Application* + chave privada exportados como `.p12`, em base64 (`base64 -i cert.p12 \| pbcopy`) |
| `MACOS_CERT_PASSWORD` | Senha do `.p12` |
| `APPLE_ID` | Apple ID da conta de desenvolvedor |
| `APPLE_TEAM_ID` | Team ID (10 caracteres) |
| `APPLE_APP_PASSWORD` | Senha específica de app gerada em account.apple.com |

**Android (assinatura de release)**:

| Secret | Conteúdo |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | Keystore (`.jks`) em base64 |
| `SCREEANER_KEYSTORE_PASSWORD` | Senha do keystore |
| `SCREEANER_KEY_ALIAS` | Alias da chave |
| `SCREEANER_KEY_PASSWORD` | Senha da chave |

Para gerar um keystore:

```bash
keytool -genkeypair -v -keystore screeaner-release.jks -alias screeaner \
  -keyalg RSA -keysize 4096 -validity 10000
```

Guarde o keystore e as senhas fora do repositório: perder a chave impede atualizar o app já instalado nos aparelhos (e, na Play Store, exige o processo de redefinição da chave de upload).

Para assinar localmente, exporte as mesmas variáveis antes do build — `SCREEANER_KEYSTORE` com o caminho absoluto do `.jks`:

```bash
export SCREEANER_KEYSTORE=$HOME/keys/screeaner-release.jks
export SCREEANER_KEYSTORE_PASSWORD=… SCREEANER_KEY_ALIAS=screeaner SCREEANER_KEY_PASSWORD=…
cd android && ./gradlew assembleRelease bundleRelease
```

## Homebrew

O cask fica em `packaging/homebrew/Casks/screeaner.rb`. Para publicar:

1. Crie o repositório público `luhno/homebrew-screeaner` com uma pasta `Casks/`.
2. A cada release, copie o cask para lá e atualize `version` (opcionalmente troque `sha256 :no_check` pelo `shasum -a 256` do zip).
3. Commit e push.

Instalação para os usuários:

```bash
brew install --cask luhno/screeaner/screeaner
```

Detalhes em `packaging/homebrew/README.md`. Sem notarização, o Gatekeeper bloqueia a primeira abertura; o usuário precisa liberar em *Ajustes do Sistema › Privacidade e Segurança*.

## Play Store

1. Crie o app no Play Console com o pacote `app.screeaner`.
2. Ative o *Play App Signing* e use o keystore acima como **chave de upload**.
3. Envie o `Screeaner-android.aab` da release (primeiro numa faixa de teste interno).
4. Textos da ficha: `android/fastlane/metadata/android/pt-BR/` e `en-US/` (`title.txt`, `short_description.txt` com até 80 caracteres, `full_description.txt`, `changelogs/<versionCode>.txt`). Dá para enviar com `fastlane supply` ou copiar manualmente.

Na declaração de permissões e na política de dados, informe que o app só se comunica com o Mac na rede local ou por USB.

## F-Droid

- O app não tem dependências externas nem serviços proprietários, então é elegível ao repositório principal.
- O F-Droid compila a partir do código-fonte da tag (`v0.4.0`) e assina com a própria chave; os metadados da ficha são lidos de `android/fastlane/metadata/android/` automaticamente.
- Para incluir, abra um merge request em `fdroiddata` com o arquivo `metadata/app.screeaner.yml` (subdir `android/app`, gradle `yes`, `AutoUpdateMode: Version`, `UpdateCheckMode: Tags`).
- Como as assinaturas são diferentes, quem instalou o APK do GitHub precisa desinstalar antes de instalar a versão do F-Droid (e vice-versa).
