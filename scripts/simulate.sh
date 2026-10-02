#!/bin/sh
# Continuously simulates flight disruptions directly in PostgreSQL.
#
# Every cycle creates a new flight together with a synthetic passenger
# manifest, then plays a random disruption scenario (delays, gate changes,
# boarding, cancellation, removal ...). Stop with Ctrl+C.
#
# Environment variables:
#   PSQL               psql command to use
#                      (default: docker compose exec -T postgres psql -U flights -d flights)
#   SIM_STEP_SECONDS   pause between steps of one flight      (default: 3)
#   SIM_PAUSE_SECONDS  pause between flights                  (default: 2)
#   SIM_PASSENGERS     base passengers per flight             (default: 24)
#   SIM_CYCLES         number of flights, 0 = run forever     (default: 0)

set -eu
# Fail the pipeline when psql fails (supported by bash, ash/busybox and recent dash).
(set -o pipefail) 2>/dev/null && set -o pipefail

PSQL=${PSQL:-docker compose exec -T postgres psql -U flights -d flights}
SIM_STEP_SECONDS=${SIM_STEP_SECONDS:-3}
SIM_PAUSE_SECONDS=${SIM_PAUSE_SECONDS:-2}
SIM_PASSENGERS=${SIM_PASSENGERS:-24}
SIM_CYCLES=${SIM_CYCLES:-0}

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
INSTALL_SQL="$SCRIPT_DIR/../db/simulator/install.sql"

case "$SIM_STEP_SECONDS$SIM_PAUSE_SECONDS$SIM_PASSENGERS$SIM_CYCLES" in
  *[!0-9.]*) echo "Simulator settings must be numeric." >&2; exit 1 ;;
esac

psql_run() {
  # shellcheck disable=SC2086 # PSQL is intentionally word-split.
  $PSQL -X -q -v ON_ERROR_STOP=1 "$@"
}

stop() {
  echo
  echo "Simulator stopped. Remove simulated data with: make simulate-clean"
  exit 0
}
trap stop INT TERM

echo "Installing simulator helpers..."
psql_run < "$INSTALL_SQL"

echo "Simulating flight disruptions (step ${SIM_STEP_SECONDS}s, ~${SIM_PASSENGERS} passengers per flight). Ctrl+C to stop."

cycle=0
while [ "$SIM_CYCLES" -eq 0 ] || [ "$cycle" -lt "$SIM_CYCLES" ]; do
  cycle=$((cycle + 1))
  psql_run -c "CALL sim.run_cycle(${SIM_STEP_SECONDS}, ${SIM_PASSENGERS})" 2>&1 \
    | sed -e 's/^NOTICE: *//'
  sleep "$SIM_PAUSE_SECONDS"
done

echo "Simulator finished after ${cycle} flights."
