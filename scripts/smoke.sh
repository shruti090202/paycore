#!/usr/bin/env bash
# Smoke test against a running PayCore API: scripts/smoke.sh https://paycore-api-gbvz.onrender.com
set -euo pipefail
API="${1:-http://localhost:8080}"
J='Content-Type: application/json'
pass() { printf '  \033[32m✓\033[0m %s\n' "$1"; }
fail() { printf '  \033[31m✗\033[0m %s\n' "$1"; exit 1; }
need() { command -v "$1" >/dev/null || fail "$1 is required"; }
need curl; need sed

echo "PayCore smoke test -> $API"

echo "1. health (may take up to a minute on a cold free instance)"
for i in $(seq 1 30); do
  if curl -sf --max-time 10 "$API/actuator/health" | grep -q '"UP"'; then pass "health UP after ~$((i*4))s"; break; fi
  [ "$i" = 30 ] && fail "health never came up"; sleep 4
done

echo "2. demo login"
TOKEN=$(curl -sf -X POST "$API/dashboard/auth/demo" -H "$J" -d '{}' | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
[ -n "$TOKEN" ] || fail "no token"; pass "token issued"
D="Authorization: Bearer $TOKEN"

echo "3. create a temporary API key"
K=$(curl -sf -X POST "$API/dashboard/api_keys" -H "$D" -H "$J" -d '{"name":"smoke"}')
KEY=$(echo "$K" | sed -n 's/.*"key":"\(sk_test_[A-Za-z0-9]*\)".*/\1/p'); KEY_ID=$(echo "$K" | sed -n 's/.*"id":"\(key_[a-z0-9]*\)".*/\1/p')
[ -n "$KEY" ] || fail "no key"; pass "key $KEY_ID"
H="Authorization: Bearer $KEY"

echo "4. create payment (with an Idempotency-Key)"
IDEM="smoke-$(date +%s)-$RANDOM"
P=$(curl -sf -X POST "$API/v1/payments" -H "$H" -H "$J" -H "Idempotency-Key: $IDEM" -d '{"amount_minor":12345,"currency":"INR","description":"smoke test","customer":{"email":"smoke@example.test"}}')
ID=$(echo "$P" | sed -n 's/.*"id":"\(pay_[a-z0-9]*\)".*/\1/p'); TOK=$(echo "$P" | sed -n 's/.*checkout\/\(cs_[A-Za-z0-9]*\)".*/\1/p')
[ -n "$ID" ] && [ -n "$TOK" ] || fail "create failed: $P"; pass "payment $ID"
R=$(curl -s -D - -o /dev/null -X POST "$API/v1/payments" -H "$H" -H "$J" -H "Idempotency-Key: $IDEM" -d '{"amount_minor":12345,"currency":"INR","description":"smoke test","customer":{"email":"smoke@example.test"}}')
echo "$R" | grep -qi "Idempotent-Replayed: true" && pass "idempotent replay" || fail "replay header missing"

echo "5. hosted checkout: session + confirm with test card 4242"
curl -sf "$API/checkout/sessions/$TOK" | grep -q '"status":"created"' && pass "session open" || fail "session not open"
C=$(curl -s -w ' HTTP:%{http_code}' -X POST "$API/checkout/sessions/$TOK/confirm" -H "$J" -d '{"card_number":"4242424242424242","exp_month":12,"exp_year":2030,"cvc":"123"}')
echo "$C" | grep -q 'HTTP:200' && echo "$C" | grep -q '"status":"captured"' && pass "captured" || fail "confirm: $C"

echo "6. real-looking card is refused"
curl -s -X POST "$API/checkout/sessions/$TOK/confirm" -H "$J" -d '{"card_number":"4111111111111111","exp_month":12,"exp_year":2030,"cvc":"123"}' | grep -q 'card_not_test_card\|checkout_session_closed' && pass "non-test card rejected" || fail "non-test card accepted?!"

echo "7. partial refund"
RF=$(curl -sf -X POST "$API/v1/payments/$ID/refunds" -H "$H" -H "$J" -d '{"amount_minor":2345}')
echo "$RF" | grep -q '"status":"succeeded"' && pass "refund succeeded" || fail "refund: $RF"
curl -sf "$API/v1/payments/$ID" -H "$H" | grep -q '"status":"partially_refunded"' && pass "payment partially_refunded" || fail "status"

echo "8. ledger + events + balance"
curl -sf "$API/v1/payments/$ID/ledger" -H "$H" | grep -q '"kind":"capture"' && pass "capture entry posted" || fail "no ledger entry"
EV=$(curl -sf "$API/v1/events?limit=10" -H "$H")
echo "$EV" | grep -q 'payment.captured' && echo "$EV" | grep -q 'refund.succeeded' && pass "events emitted" || fail "events: $EV"
curl -sf "$API/v1/balance" -H "$H" | grep -q '"balance_minor"' && pass "balance readable" || fail "balance"

echo "9. rate-limit headers present"
curl -s -D - -o /dev/null "$API/v1/account" -H "$H" | grep -qi "X-RateLimit-Limit" && pass "X-RateLimit-* headers" || fail "no rate limit headers"

echo "10. revoke the temporary key"
curl -sf -X DELETE "$API/dashboard/api_keys/$KEY_ID" -H "$D" -o /dev/null && pass "key revoked" || fail "revoke"
curl -s -o /dev/null -w '%{http_code}' "$API/v1/account" -H "$H" | grep -q 401 && pass "revoked key rejected" || fail "revoked key still works"

echo "All good."
