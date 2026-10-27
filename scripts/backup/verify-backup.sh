#!/usr/bin/env bash
# =============================================================================
# Test de restauration d'une sauvegarde GestStock : déchiffre l'archive, contrôle les empreintes du
# manifeste, restaure chaque base dans une base temporaire, vérifie qu'elle contient des tables, puis
# supprime ces bases temporaires. Ne touche jamais aux bases de production.
#
# Usage : verify-backup.sh <archive .tar.gpg>
# Variables : GESTSTOCK_BACKUP_PASSPHRASE (obligatoire),
#             GESTSTOCK_VERIFY_URL  postgresql://utilisateur:motdepasse@hôte d'un serveur de test
#                                   (droit CREATE DATABASE ; jamais la production)
# =============================================================================
set -euo pipefail

ARCHIVE="${1:?Usage : verify-backup.sh <archive .tar.gpg>}"
: "${GESTSTOCK_BACKUP_PASSPHRASE:?GESTSTOCK_BACKUP_PASSPHRASE manquante}"
: "${GESTSTOCK_VERIFY_URL:?GESTSTOCK_VERIFY_URL manquant (serveur de test)}"

WORK="$(mktemp -d)"
created=()
cleanup() {
  for db in "${created[@]}"; do
    psql "$GESTSTOCK_VERIFY_URL/postgres" -q -c "drop database if exists \"$db\"" >/dev/null 2>&1 || true
  done
  rm -rf "$WORK"
}
trap cleanup EXIT

gpg --batch --quiet --decrypt --pinentry-mode loopback --passphrase-fd 3 "$ARCHIVE" 3<<<"$GESTSTOCK_BACKUP_PASSPHRASE" \
  | tar -C "$WORK" -xf -
DIR="$(find "$WORK" -mindepth 1 -maxdepth 1 -type d -name 'geststock-backup-*' | head -1)"
[ -n "$DIR" ] || { echo "ERREUR : archive sans dossier de sauvegarde" >&2; exit 1; }

# Empreintes : chaque fichier doit correspondre au manifeste écrit lors de la sauvegarde.
grep -E '^[0-9a-f]{64}  ' "$DIR/MANIFEST.txt" | while read -r sum _size file; do
  echo "$sum  $DIR/$file" | sha256sum --check --quiet - || { echo "ERREUR : empreinte invalide : $file" >&2; exit 1; }
done

failures=0
count=0
for dump in "$DIR"/*.dump; do
  [ -e "$dump" ] || continue
  name="$(basename "$dump" .dump)"
  target="verify_${name}_$$"
  psql "$GESTSTOCK_VERIFY_URL/postgres" -q -v ON_ERROR_STOP=1 -c "create database \"$target\"" >/dev/null
  created+=("$target")
  if pg_restore --no-owner --no-acl --exit-on-error -d "$GESTSTOCK_VERIFY_URL/$target" "$dump"; then
    tables="$(psql "$GESTSTOCK_VERIFY_URL/$target" -At -c \
      "select count(*) from information_schema.tables where table_schema not in ('pg_catalog','information_schema')")"
    if [ "$tables" -gt 0 ]; then
      echo "OK   $name : $tables table(s) restaurée(s)"
      count=$((count + 1))
    else
      echo "ÉCHEC $name : aucune table restaurée" >&2
      failures=$((failures + 1))
    fi
  else
    echo "ÉCHEC $name : restauration impossible" >&2
    failures=$((failures + 1))
  fi
done

if [ "$count" -eq 0 ] || [ "$failures" -gt 0 ]; then
  echo "ERREUR : sauvegarde non restaurable ($failures échec(s), $count base(s) vérifiée(s))." >&2
  exit 1
fi
echo "Sauvegarde restaurable : $count base(s) vérifiée(s)."
