#!/usr/bin/env bash
# =============================================================================
# seed-local-data.sh — Seeds demo accounts into the local Redis container.
#
# Run this ONCE after `docker-compose up -d` and before starting the app.
# Only intended for local development / Swagger UI demos.
#
# Seeded accounts:
#   user-001  →  $100.00  (mid-tier demo:  $10 payment → $89.70)
#   user-002  →  $500.00  (high-tier demo: $75 payment → $421.25)
#   user-low  →   $50.00  (low-tier demo:   $5 payment → $44.95)
# =============================================================================

set -e

CONTAINER="customer-care-redis"

echo "Seeding demo accounts into Redis container '$CONTAINER'..."

REDIS_CLASS="com.customercare.infra.redis.entity.AccountEntity"

docker exec "$CONTAINER" redis-cli \
  HSET "account:user-001" "_class" "$REDIS_CLASS" "userId" "user-001" "balance" "100.00"
docker exec "$CONTAINER" redis-cli SADD "account" "user-001"

docker exec "$CONTAINER" redis-cli \
  HSET "account:user-002" "_class" "$REDIS_CLASS" "userId" "user-002" "balance" "500.00"
docker exec "$CONTAINER" redis-cli SADD "account" "user-002"

docker exec "$CONTAINER" redis-cli \
  HSET "account:user-low" "_class" "$REDIS_CLASS" "userId" "user-low" "balance" "50.00"
docker exec "$CONTAINER" redis-cli SADD "account" "user-low"

echo ""
echo "Done. Accounts in Redis:"
docker exec "$CONTAINER" redis-cli HGETALL "account:user-001"
docker exec "$CONTAINER" redis-cli HGETALL "account:user-002"
docker exec "$CONTAINER" redis-cli HGETALL "account:user-low"
echo ""
echo "Swagger UI: http://localhost:8080/swagger-ui.html"

