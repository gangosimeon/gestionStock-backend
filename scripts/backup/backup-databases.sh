#!/usr/bin/env bash
# =============================================================================
# Sauvegarde chiffrée de toutes les bases GestStock (format pg_dump « custom »).
#
#   base historique   : GESTSTOCK_BACKUP_LEGACY_DB   (défaut geststock), compte administrateur
#   base plateforme   : GESTSTOCK_BACKUP_PLATFORM_DB (défaut geststock_platform), compte administrateur
#   bases d'entreprise: lues dans platform.company_database (mode DEDICATED), compte provisionneur
#                       (seul propriétaire autorisé à s'y connecter)
#
# Variables obligatoires (jamais affichées) :
#   GESTSTOCK_BACKUP_ADMIN_URL        postgresql://utilisateur:motdepasse@hôte   (sans nom de base)
#   GESTSTOCK_BACKUP_PROVISIONER_URL  postgresql://geststock_provisioner:motdepasse@hôte
#   GESTSTOCK_BACKUP_PASSPHRASE       phrase secrète du chiffrement (AES-256)
# Facultatives : GESTSTOCK_BACKUP_SSLMODE (défaut require), GESTSTOCK_BACKUP_DIR (défaut ./backup-out)
#
# Résultat : <dir>/geststock-backup-<AAAAMMJJ-HHMM>.tar.gpg et son empreinte .sha256.
# Toutes les bases sont tentées ; le script échoue (code 1) si l'une d'elles n'a pas pu être exportée.
# =============================================================================
set -euo pipefail

: "${GESTSTOCK_BACKUP_ADMIN_URL:?GESTSTOCK_BACKUP_ADMIN_URL manquant}"
: "${GESTSTOCK_BACKUP_PROVISIONER_URL:?GESTSTOCK_BACKUP_PROVISIONER_URL manquant}"
: "${GESTSTOCK_BACKUP_PASSPHRASE:?GESTSTOCK_BACKUP_PASSPHRASE manquante}"
LEGACY_DB="${GESTSTOCK_BACKUP_LEGACY_DB:-geststock}"
PLATFORM_DB="${GESTSTOCK_BACKUP_PLATFORM_DB:-geststock_platform}"
SSLMODE="${GESTSTOCK_BACKUP_SSLMODE:-require}"
OUT_DIR="${GESTSTOCK_BACKUP_DIR:-./backup-out}"

STAMP="$(date -u +%Y%m%d-%H%M)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$OUT_DIR" "$WORK/geststock-backup-$STAMP"
DUMPS="$WORK/geststock-backup-$STAMP"

# Noms de base : uniquement minuscules, chiffres et « _ » (jamais injectés tels quels sinon).
valid_name() { [[ "$1" =~ ^[a-z0-9_]{1,63}$ ]]; }

url() { printf '%s/%s?sslmode=%s' "$1" "$2" "$SSLMODE"; }

failures=0
dump() { # dump <url de serveur> <base>
  local server="$1" db="$2"
  if ! valid_name "$db"; then
    echo "ERREUR : nom de base refusé : $db" >&2
    failures=$((failures + 1))
    return
  fi
  if pg_dump --format=custom --no-owner --no-acl --file "$DUMPS/$db.dump" "$(url "$server" "$db")"; then
    echo "OK   $db ($(du -h "$DUMPS/$db.dump" | cut -f1))"
  else
    echo "ÉCHEC $db" >&2
    rm -f "$DUMPS/$db.dump"
    failures=$((failures + 1))
  fi
}

echo "Sauvegarde GestStock $STAMP (UTC)"
dump "$GESTSTOCK_BACKUP_ADMIN_URL" "$LEGACY_DB"
# En développement, la plateforme est un schéma de la base historique : une seule exportation.
if [ "$PLATFORM_DB" != "$LEGACY_DB" ]; then
  dump "$GESTSTOCK_BACKUP_ADMIN_URL" "$PLATFORM_DB"
fi

companies="$(psql "$(url "$GESTSTOCK_BACKUP_ADMIN_URL" "$PLATFORM_DB")" -At -v ON_ERROR_STOP=1 \
  -c "select database_name from platform.company_database where mode = 'DEDICATED' order by 1")" || {
  echo "ERREUR : liste des bases d'entreprise illisible" >&2
  failures=$((failures + 1))
  companies=""
}
for db in $companies; do
  dump "$GESTSTOCK_BACKUP_PROVISIONER_URL" "$db"
done

# Manifeste : contenu de l'archive (bases, tailles, empreintes) pour vérification à la restauration.
(
  cd "$DUMPS"
  {
    echo "date_utc=$STAMP"
    echo "legacy_db=$LEGACY_DB"
    echo "platform_db=$PLATFORM_DB"
    for f in *.dump; do
      [ -e "$f" ] || continue
      echo "$(sha256sum "$f" | cut -d' ' -f1)  $(stat -c %s "$f")  $f"
    done
  } > MANIFEST.txt
)

ARCHIVE="$OUT_DIR/geststock-backup-$STAMP.tar.gpg"
tar -C "$WORK" -cf - "geststock-backup-$STAMP" \
  | gpg --batch --yes --quiet --symmetric --cipher-algo AES256 --pinentry-mode loopback \
        --passphrase-fd 3 --output "$ARCHIVE" 3<<<"$GESTSTOCK_BACKUP_PASSPHRASE"
(cd "$OUT_DIR" && sha256sum "$(basename "$ARCHIVE")" > "$(basename "$ARCHIVE").sha256")

echo "Archive chiffrée : $ARCHIVE ($(du -h "$ARCHIVE" | cut -f1))"
if [ "$failures" -gt 0 ]; then
  echo "ERREUR : $failures base(s) non sauvegardée(s)." >&2
  exit 1
fi
