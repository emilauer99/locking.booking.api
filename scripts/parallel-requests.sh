#!/usr/bin/env bash
# Sends parallel requests against a running api and prints the http status codes,
# to show that no double booking is possible.
#
# usage: scripts/parallel-requests.sh [base-url] [variant] [parallel-requests]
#   base-url           default http://localhost:8080 (load balancer: http://localhost:8090)
#   variant            optimistic | pessimistic, default: both
#   parallel-requests  default 20
#
# requires bash, curl and xargs (on windows e.g. git bash)
set -euo pipefail

BASE_URL="${1:-http://localhost:8080}"
VARIANTS="${2:-optimistic pessimistic}"
N="${3:-20}"
JSON='Content-Type: application/json'

# a random room id per run, so the script can be executed several times
ROOM_BASE=$(( (RANDOM * 32768 + RANDOM) % 1000000 + 100000 ))

status_counts() {
  sort | uniq -c | awk '{ printf "    %3d x %s\n", $1, $2 }'
}

booking_status() {
  curl -s "$BASE_URL/optimistic/booking/$1" | grep -o '"status":"[A-Z]*"' | cut -d'"' -f4
}

reserve() {
  curl -s -H "$JSON" -d "{\"name\":\"Script\",\"roomId\":$1,\"start\":\"$2\",\"finish\":\"$3\"}" "$BASE_URL/$4/booking"
}

export BASE_URL JSON

for VARIANT in $VARIANTS; do
  ROOM=$(( ROOM_BASE++ ))
  echo "=== $VARIANT, room $ROOM, $N parallel requests against $BASE_URL"

  echo "1) $N parallel reservations of the same period (expected: 1 x 201, rest 409)"
  seq 1 "$N" | xargs -P "$N" -I{} curl -s -o /dev/null -w "%{http_code}\n" -H "$JSON" \
    -d "{\"name\":\"Script\",\"roomId\":$ROOM,\"start\":\"2027-02-01\",\"finish\":\"2027-02-05\"}" \
    "$BASE_URL/$VARIANT/booking" | status_counts
  echo "    bookings of room $ROOM: $(curl -s "$BASE_URL/optimistic/booking" | grep -o "\"roomId\":$ROOM," | wc -l | tr -d ' ')"

  ROOM=$(( ROOM_BASE++ ))
  ID=$(reserve "$ROOM" 2027-03-01 2027-03-05 "$VARIANT" | grep -o '"id":[0-9]*' | cut -d: -f2)
  echo "2) $N parallel confirmations of booking $ID (expected: 1 x 201, rest 409)"
  seq 1 "$N" | xargs -P "$N" -I{} curl -s -o /dev/null -w "%{http_code}\n" -H "$JSON" \
    -d "{\"bookingId\":$ID}" "$BASE_URL/$VARIANT/bookingconfirmation" | status_counts
  echo "    status of booking $ID: $(booking_status "$ID")"

  ROOM=$(( ROOM_BASE++ ))
  ID=$(reserve "$ROOM" 2027-04-01 2027-04-05 "$VARIANT" | grep -o '"id":[0-9]*' | cut -d: -f2)
  echo "3) $N parallel requests, half confirm and half delete booking $ID (expected: either confirmed or cancelled)"
  seq 1 "$N" | xargs -P "$N" -I{} sh -c "
    if [ \$(( {} % 2 )) -eq 0 ]; then
      curl -s -o /dev/null -w 'confirm %{http_code}\n' -H '$JSON' -d '{\"bookingId\":$ID}' '$BASE_URL/$VARIANT/bookingconfirmation'
    else
      curl -s -o /dev/null -w 'delete  %{http_code}\n' -X DELETE '$BASE_URL/$VARIANT/booking/$ID'
    fi" | sort | uniq -c | awk '{ printf "    %3d x %s %s\n", $1, $2, $3 }'
  echo "    status of booking $ID: $(booking_status "$ID")"
  echo
done
