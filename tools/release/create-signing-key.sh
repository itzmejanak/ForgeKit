#!/usr/bin/env bash
# Creates ForgeKit's release signing key OUTSIDE the repository and writes the base64 copy that
# goes into the RELEASE_KEYSTORE_BASE64 GitHub secret. Run it yourself; never commit the keystore
# or share its password.
#
#   bash tools/release/create-signing-key.sh [output-dir]      (default: ~/.forgekit-signing)
#
# Requires `keytool` (part of every JDK) and `base64`. See Docs/RELEASING.md.
set -euo pipefail
export LC_ALL=C

out_dir="${1:-$HOME/.forgekit-signing}"
alias_name="forgekit"

repo_root="$(git rev-parse --show-toplevel 2>/dev/null || true)"
mkdir -p "$out_dir"
out_dir="$(cd "$out_dir" && pwd -P)"
if [ -n "$repo_root" ]; then
  repo_root="$(cd "$repo_root" && pwd -P)"
  case "$out_dir/" in
    "$repo_root/"|"$repo_root/"*)
      echo "Refusing to create the keystore inside the repository: $out_dir" >&2
      exit 1 ;;
  esac
fi
keystore="$out_dir/forgekit-release.jks"
if [ -e "$keystore" ]; then
  echo "A keystore already exists at $keystore. Move it away first if you really want a new key." >&2
  exit 1
fi
command -v keytool >/dev/null || { echo "keytool not found: install a JDK first." >&2; exit 1; }

read -rsp "Choose a keystore password (at least 12 characters): " password; echo
read -rsp "Repeat the password: " password_again; echo
[ "$password" = "$password_again" ] || { echo "Passwords do not match." >&2; exit 1; }
[ "${#password}" -ge 12 ] || { echo "Use at least 12 characters." >&2; exit 1; }

chmod 700 "$out_dir"

# PKCS12 keystores use one password for the store and the key. The password is passed through
# the environment so it never appears in the process list or shell history.
FORGEKIT_NEW_KEY_PASSWORD="$password" keytool -genkeypair \
  -keystore "$keystore" -storetype PKCS12 \
  -alias "$alias_name" -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=ForgeKit Release, O=ForgeKit" \
  -storepass:env FORGEKIT_NEW_KEY_PASSWORD \
  -keypass:env FORGEKIT_NEW_KEY_PASSWORD
chmod 600 "$keystore"

if base64 --help 2>&1 | grep -q -- '-w'; then
  base64 -w0 "$keystore" > "$keystore.base64"
else
  base64 "$keystore" | tr -d '\n' > "$keystore.base64"
fi
chmod 600 "$keystore.base64"

cat <<INFO

Created:
  $keystore            (back this up somewhere safe and offline)
  $keystore.base64     (paste its content into a GitHub secret, then delete this file)

Add these secrets to the "production" environment
(GitHub -> Settings -> Environments -> production -> Environment secrets):

  RELEASE_KEYSTORE_BASE64   content of $keystore.base64
  RELEASE_KEYSTORE_PASSWORD the password you just chose
  RELEASE_KEY_ALIAS         $alias_name
  RELEASE_KEY_PASSWORD      the same password

Certificate fingerprint (publish it so users can verify release APKs):
INFO
FORGEKIT_NEW_KEY_PASSWORD="$password" keytool -list -v \
  -keystore "$keystore" -storetype PKCS12 -alias "$alias_name" \
  -storepass:env FORGEKIT_NEW_KEY_PASSWORD | grep -E "SHA256:"
