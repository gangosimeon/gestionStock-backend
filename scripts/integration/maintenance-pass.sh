#!/usr/bin/env bash
# Un passage de maintenance de l'intégration JeCréeMaBoutique (lot 3, G20e ; plan § 33 à § 37) : POST sur la route
# interne du backend, affichage du rapport agrégé (sans identifiant, G20-D11), puis code de sortie :
#   0 : passage COMPLETED ou PARTIAL sans alerte CRITICAL ; ou 409 (un passage est déjà en cours, G20-D4) ;
#   1 : statut FAILED, alerte CRITICAL, réponse illisible, ou configuration à corriger (401, 403, 404, variable absente) ;
#   2 : backend injoignable ou en erreur après toutes les tentatives (réveil de l'hébergeur trop long, panne).
# La maintenance ne supprime aucune donnée tant que la purge est en SIMULATION (G20-D5, plan § 36).
#
# Environnement :
#   GESTSTOCK_MAINTENANCE_URL    adresse https de la route (…/internal/integration/maintenance)
#   GESTSTOCK_MAINTENANCE_TOKEN  secret du cron ; jamais affiché, transmis à curl par l'entrée standard
#   MAINTENANCE_ATTEMPTS (3), MAINTENANCE_RETRY_DELAY (30 s), MAINTENANCE_MAX_TIME (120 s, réveil compris), PYTHON
set -euo pipefail

url="${GESTSTOCK_MAINTENANCE_URL:-}"
attempts="${MAINTENANCE_ATTEMPTS:-3}"
delay="${MAINTENANCE_RETRY_DELAY:-30}"
max_time="${MAINTENANCE_MAX_TIME:-120}"
python="${PYTHON:-python3}"

if [[ -z "$url" || -z "${GESTSTOCK_MAINTENANCE_TOKEN:-}" ]]; then
  echo "::error::GESTSTOCK_MAINTENANCE_URL (variable) et GESTSTOCK_MAINTENANCE_TOKEN (secret) sont requis."
  exit 1
fi
if [[ "$url" != https://* ]]; then
  echo "::error::GESTSTOCK_MAINTENANCE_URL doit commencer par https:// (le secret ne circule jamais en clair)."
  exit 1
fi

body="$(mktemp)"
trap 'rm -f "$body"' EXIT

# Rapport : affiché tel quel (agrégé, sans identifiant) ; échec sur FAILED ou sur une alerte CRITICAL.
evaluate() {
  "$python" - "$body" <<'PY'
import json
import sys

try:
    with open(sys.argv[1], encoding="utf-8") as source:
        report = json.load(source)
    status = report["status"]
    alerts = report.get("alerts", [])
except (ValueError, KeyError, TypeError):
    print("::error::Rapport de maintenance illisible.")
    sys.exit(1)
print(json.dumps(report, ensure_ascii=False, indent=2))
critical = False
for alert in alerts:
    severity = alert.get("severity")
    level = "error" if severity == "CRITICAL" else "warning"
    critical = critical or severity == "CRITICAL"
    print(f"::{level}::Alerte {alert.get('type')} ({severity}) : {alert.get('count')}")
if status == "FAILED":
    print("::error::Passage FAILED (bail perdu ou erreur générale) : voir les journaux du backend, génération "
          f"{report.get('generation')}.")
    sys.exit(1)
if critical:
    sys.exit(1)
if status == "PARTIAL":
    print("::notice::Passage partiel (budget épuisé) : reprise au passage suivant.")
sys.exit(0)
PY
}

for ((attempt = 1; attempt <= attempts; attempt++)); do
  code="$(printf 'header = "Authorization: Bearer %s"\n' "$GESTSTOCK_MAINTENANCE_TOKEN" \
    | curl --config - --silent --show-error --request POST --header 'Accept: application/json' \
      --connect-timeout 30 --max-time "$max_time" --output "$body" --write-out '%{http_code}' "$url")" || code="000"
  case "$code" in
    200)
      evaluate
      exit $?
      ;;
    409)
      echo "::notice::Un passage de maintenance est déjà en cours (409) : rien à faire."
      exit 0
      ;;
    401 | 403 | 404)
      echo "::error::Réponse $code : vérifier le secret du cron et son empreinte, l'adresse et l'activation de la maintenance sur le backend."
      exit 1
      ;;
    429 | 5?? | 000)
      echo "::warning::Tentative $attempt/$attempts : réponse $code."
      if ((attempt < attempts)); then
        sleep "$delay"
      fi
      ;;
    *)
      echo "::error::Réponse inattendue : $code."
      exit 1
      ;;
  esac
done
echo "::error::Backend injoignable ou en erreur après $attempts tentatives."
exit 2
