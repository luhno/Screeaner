#!/bin/bash
# Creates a stable, self-signed code-signing identity ("Screeaner Local Signing")
# in the login keychain. bundle.sh picks it up automatically, so macOS keeps the
# Screen Recording / Accessibility grants across rebuilds (they are tied to the
# signature; ad-hoc signatures change on every build).
#
# Safe to run more than once: does nothing if the identity already exists.
# macOS will ask for your password to trust the certificate for code signing.
set -euo pipefail

NAME="Screeaner Local Signing"
KEYCHAIN="${KEYCHAIN:-$HOME/Library/Keychains/login.keychain-db}"

if security find-identity -v -p codesigning | grep -q "\"$NAME\""; then
    echo "A identidade \"$NAME\" já existe. Nada a fazer."
    exit 0
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

cat > "$TMP/cert.cnf" <<CNF
[ req ]
distinguished_name = dn
x509_extensions    = ext
prompt             = no

[ dn ]
CN = $NAME

[ ext ]
basicConstraints       = critical, CA:false
keyUsage               = critical, digitalSignature
extendedKeyUsage       = critical, codeSigning
subjectKeyIdentifier   = hash
CNF

echo "Gerando certificado autoassinado \"$NAME\" (válido por 10 anos)…"
openssl req -new -x509 -nodes -newkey rsa:2048 -sha256 -days 3650 \
    -config "$TMP/cert.cnf" -keyout "$TMP/key.pem" -out "$TMP/cert.pem"

# `security import` only understands the legacy PKCS#12 encryption; OpenSSL 3
# defaults to AES, so ask for the legacy algorithms there. (LibreSSL, the
# /usr/bin/openssl on macOS, already uses them and has no -legacy flag.)
P12_PASS="screeaner-$$-$RANDOM"
LEGACY=()  # (the ${x[@]+…} form below keeps bash 3.2 + set -u happy with an empty array)
if openssl version | grep -q '^OpenSSL 3'; then
    LEGACY=(-legacy)
fi
openssl pkcs12 -export ${LEGACY[@]+"${LEGACY[@]}"} -name "$NAME" \
    -inkey "$TMP/key.pem" -in "$TMP/cert.pem" \
    -out "$TMP/identity.p12" -passout "pass:$P12_PASS"

echo "Importando no chaveiro $KEYCHAIN…"
security import "$TMP/identity.p12" -k "$KEYCHAIN" -P "$P12_PASS" \
    -T /usr/bin/codesign

echo "Marcando o certificado como confiável para assinatura de código (o macOS vai pedir sua senha)…"
security add-trusted-cert -r trustRoot -p codeSign -k "$KEYCHAIN" "$TMP/cert.pem"

if security find-identity -v -p codesigning | grep -q "\"$NAME\""; then
    echo "OK: identidade \"$NAME\" pronta. Rode ./scripts/bundle.sh."
else
    echo "Aviso: o certificado foi importado, mas ainda não aparece como identidade válida." >&2
    echo "Confira em Acesso às Chaves › Certificados se ele está como confiável para assinatura de código." >&2
    exit 1
fi
