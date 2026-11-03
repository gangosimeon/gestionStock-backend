#!/usr/bin/env bash
# Vérification hors ligne de maintenance-pass.sh (lot 3, G20e) : un faux curl, placé en tête du PATH, rejoue des
# réponses du backend ; aucun appel réseau. Usage : bash scripts/integration/maintenance-pass.test.sh
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
if [[ -z "${PYTHON:-}" ]]; then
  if command -v python3 >/dev/null 2>&1 && python3 -c 'pass' >/dev/null 2>&1; then PYTHON=python3; else PYTHON=python; fi
fi
export PYTHON

# Faux curl : codes successifs dans FAKE_CODES (« 503 200 »), corps dans FAKE_BODY ; refuse un secret en argument.
mkdir -p "$work/bin"
cat > "$work/bin/curl" <<'FAKE'
#!/usr/bin/env bash
config="$(cat)"
for argument in "$@"; do
  if [[ "$argument" == *"$FAKE_SECRET"* ]]; then echo "secret passé en argument" >&2; exit 99; fi
done
[[ "$config" == "header = \"Authorization: Bearer $FAKE_SECRET\"" ]] || { echo "en-tête absent de l'entrée standard" >&2; exit 98; }
calls="$(cat "$FAKE_STATE" 2>/dev/null || echo 0)"
calls=$((calls + 1))
echo "$calls" > "$FAKE_STATE"
read -r -a codes <<< "$FAKE_CODES"
code="${codes[$((calls - 1))]:-${codes[-1]}}"
output=""
previous=""
for argument in "$@"; do
  if [[ "$previous" == "--output" ]]; then output="$argument"; fi
  previous="$argument"
done
if [[ "$code" == "000" ]]; then echo "curl: (28) délai dépassé" >&2; printf '000'; exit 28; fi
cp "$FAKE_BODY" "$output"
printf '%s' "$code"
FAKE
chmod +x "$work/bin/curl"

failures=0
secret="secret-de-test-$RANDOM-$RANDOM-ne-doit-jamais-apparaitre"

# run <nom> <codes> <rapport JSON> <code de sortie attendu> [variables...]
run() {
  local name="$1" codes="$2" body="$3" expected="$4"
  shift 4
  printf '%s' "$body" > "$work/body.json"
  rm -f "$work/state"
  local status=0
  env PATH="$work/bin:$PATH" FAKE_SECRET="$secret" FAKE_CODES="$codes" FAKE_BODY="$work/body.json" \
    FAKE_STATE="$work/state" GESTSTOCK_MAINTENANCE_URL="https://geststock.invalid/internal/integration/maintenance" \
    GESTSTOCK_MAINTENANCE_TOKEN="$secret" MAINTENANCE_RETRY_DELAY=0 "$@" \
    bash "$here/maintenance-pass.sh" > "$work/out.txt" 2>&1 || status=$?
  local calls
  calls="$(cat "$work/state" 2>/dev/null || echo 0)"
  if grep -q -- "$secret" "$work/out.txt"; then
    echo "ÉCHEC $name : le secret apparaît dans la sortie"; failures=$((failures + 1)); return
  fi
  if [[ "$status" != "$expected" ]]; then
    echo "ÉCHEC $name : sortie $status, attendue $expected"; sed 's/^/    /' "$work/out.txt"; failures=$((failures + 1)); return
  fi
  echo "ok    $name (sortie $status, $calls appel(s))"
}

completed='{"status":"COMPLETED","mode":"SIMULATION","generation":1,"durationMs":10,"companies":{"active":1,"visited":1,"skippedUpgrade":0,"errors":0,"interrupted":0,"remaining":0},"purge":{"eligible":0,"deleted":0,"batches":0,"blocked":0,"maxLockWaitMs":0},"links":{"active":0,"suspended":0},"cleanup":{"expiredPendingLinks":0},"alerts":[],"authFailures":{"count":0,"scope":"INSTANCE"}}'
partial="${completed/\"COMPLETED\"/\"PARTIAL\"}"
partial="${partial/\"alerts\":[]/\"alerts\":[{\"type\":\"NO_ACTIVITY\",\"severity\":\"WARNING\",\"count\":1}]}"
critical="${completed/\"alerts\":[]/\"alerts\":[{\"type\":\"COMPANY_ERROR\",\"severity\":\"CRITICAL\",\"count\":1}]}"
failed="${completed/\"COMPLETED\"/\"FAILED\"}"
problem='{"type":"about:blank","title":"Erreur","status":401,"code":"INVALID_TOKEN","path":"/internal/integration/maintenance"}'

run "passage terminé"                         "200"         "$completed" 0
run "passage partiel, alerte WARNING"         "200"         "$partial"   0
run "alerte CRITICAL"                         "200"         "$critical"  1
run "statut FAILED"                           "200"         "$failed"    1
run "passage déjà en cours (409)"             "409"         "$problem"   0
run "secret refusé (401)"                     "401"         "$problem"   1
run "maintenance désactivée (404)"            "404"         "$problem"   1
run "réveil : 503 puis 200"                   "503 200"     "$completed" 0
run "délai dépassé puis 200"                  "000 200"     "$completed" 0
run "débit (429) puis 200"                    "429 200"     "$completed" 0
run "injoignable après 3 tentatives"          "503 503 503" "$completed" 2
run "secret absent"                           "200"         "$completed" 1 GESTSTOCK_MAINTENANCE_TOKEN=
run "adresse sans https"                      "200"         "$completed" 1 GESTSTOCK_MAINTENANCE_URL=http://geststock.invalid/internal/integration/maintenance
run "réponse 200 illisible"                   "200"         "pas du json" 1

if [[ "$failures" -gt 0 ]]; then
  echo "$failures échec(s)"
  exit 1
fi
echo "Tous les cas passent."
