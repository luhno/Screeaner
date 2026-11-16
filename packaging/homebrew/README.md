# Homebrew cask

`Casks/screeaner.rb` instala o `Screeaner-mac.zip` publicado nas Releases do GitHub.

## Publicar no tap

1. Crie (uma vez) o repositório público `luhno/homebrew-screeaner` com uma pasta `Casks/`.
2. A cada release, copie `Casks/screeaner.rb` para lá e atualize `version` (sem o `v`).
   Opcional: troque `sha256 :no_check` pelo hash real:
   `shasum -a 256 Screeaner-mac.zip`.
3. Valide antes de publicar:
   ```bash
   brew audit --cask --new luhno/screeaner/screeaner
   brew install --cask --verbose luhno/screeaner/screeaner
   ```
4. Faça commit e push no tap.

## Instalar

```bash
brew install --cask luhno/screeaner/screeaner
```

(equivale a `brew tap luhno/screeaner` seguido de `brew install --cask screeaner`.)
