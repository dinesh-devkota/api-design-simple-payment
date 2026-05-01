# Q&A 27 — Docker, Docker Compose & Containerisation

> Everything a developer should know about `docker-compose.yml`, container networking, Redis in Docker, and production container patterns.

---

## Q1: Walk through `docker-compose.yml` — what does each section do?

**A:** Based on the project's `docker-compose.yml`:

```yaml
version: "3.8"
services:
  redis:
    image: redis:7-alpine        # official Redis 7 on Alpine Linux (minimal ~30MB)
    ports:
      - "6379:6379"              # host_port:container_port
    volumes:
      - redis-data:/data         # named volume — survives container restarts
    command: redis-server --appendonly yes   # enable AOF persistence
    seed:
      image: redis:7-alpine
      depends_on:
        - redis
      volumes:
        - ./scripts/seed.sh:/seed.sh
      command: sh /seed.sh
volumes:
  redis-data:
```

- `image: redis:7-alpine` — pins the major version, uses Alpine to minimise image size.
- `ports: "6379:6379"` — binds container port 6379 to host port 6379, so the Spring app running on the host connects via `localhost:6379`.
- `volumes: redis-data:/data` — Redis writes RDB/AOF files to `/data`. A named volume persists this data even if `docker-compose down` is run (data survives unless `docker-compose down -v`).
- `command: redis-server --appendonly yes` — overrides the default Redis command to enable Append-Only File persistence, so data survives Redis restarts.
- `depends_on: redis` — Docker Compose starts `redis` before `seed`, but this only guarantees **start order**, NOT that Redis is ready to accept connections (see Q5).

---

## Q2: Why `redis:7-alpine` and not just `redis:latest`?

**A:**

| Image | Risk | Reason |
|---|---|---|
| `redis:latest` | High | Automatically pulls the newest major version — breaking changes on next `docker pull` |
| `redis:7` | Medium | Pins major version — gets 7.x patches automatically |
| `redis:7-alpine` | Low | Pins major, minimal base OS, smaller attack surface (~30MB vs ~120MB) |
| `redis:7.2.4-alpine` | Very Low | Fully pinned — reproducible, but requires manual updates |

**For development:** `redis:7-alpine` is a reasonable balance.
**For production:** pin the full version tag and scan with `trivy` or `snyk` for CVEs.

---

## Q3: What is the difference between `CMD` and `ENTRYPOINT` in a Dockerfile?

**A:**

| | `CMD` | `ENTRYPOINT` |
|---|---|---|
| Purpose | Default command (overridable) | Fixed executable (not overridable without `--entrypoint`) |
| Override | `docker run image my_command` replaces CMD | `docker run image arg` APPENDS to ENTRYPOINT |
| Shell form | `CMD redis-server` | `ENTRYPOINT redis-server` |
| Exec form | `CMD ["redis-server"]` | `ENTRYPOINT ["redis-server"]` |

Redis's official Dockerfile uses `ENTRYPOINT ["docker-entrypoint.sh"]` + `CMD ["redis-server"]`. The `command:` override in Docker Compose replaces `CMD`.

**In the project:** `command: redis-server --appendonly yes` replaces the default CMD to pass the `--appendonly yes` flag.

---

## Q4: What does `--appendonly yes` do and what is the tradeoff?

**A:** Redis has two persistence mechanisms:

| Mechanism | How | Pros | Cons |
|---|---|---|---|
| RDB (default) | Periodic snapshot | Fast restarts, compact file | Data loss between snapshots |
| AOF (appendonly) | Log every write command | Minimal data loss (configurable fsync) | Larger files, slower restarts |
| Both | RDB + AOF | Best durability | Most disk usage |

`--appendonly yes` enables AOF. With default `appendfsync everysec`, Redis calls `fsync` every second — at most 1 second of data loss on crash.

**For a payments application:** data loss is unacceptable — AOF (or both AOF + RDB) is the right choice. In production, additionally enable Redis Cluster or Sentinel for high availability.

---

## Q5: What is the `depends_on` problem and how do you solve it?

**A:** `depends_on` only guarantees that Docker Compose **starts** the dependent container first. It does NOT wait for the service inside to be **ready** (e.g., Redis accepting TCP connections).

The `seed` service might run `redis-cli` commands before Redis has finished initialising — connection refused error.

**Solutions:**

1. **`healthcheck` + `condition: service_healthy`:**
```yaml
redis:
  image: redis:7-alpine
  healthcheck:
    test: ["CMD", "redis-cli", "ping"]
    interval: 5s
    timeout: 3s
    retries: 5

seed:
  depends_on:
    redis:
      condition: service_healthy
```
The seed container only starts after `redis-cli ping` returns `PONG`.

2. **`wait-for-it.sh` or `dockerize`:** Shell scripts that poll TCP until the port is open.

3. **Retry logic in the seed script:** The `seed.sh` script can loop `redis-cli ping` until it succeeds before running the data load.

---

## Q6: How would you add the Spring Boot application itself to `docker-compose.yml`?

**A:**

```yaml
app:
  build:
    context: .
    dockerfile: Dockerfile
  ports:
    - "8080:8080"
  environment:
    - SPRING_DATA_REDIS_HOST=redis    # use service name, not localhost
    - SPRING_DATA_REDIS_PORT=6379
  depends_on:
    redis:
      condition: service_healthy
```

**Critical:** `SPRING_DATA_REDIS_HOST=localhost` would NOT work inside Docker Compose — containers communicate via service names on the Docker bridge network. The Spring app container must connect to `redis:6379`, not `localhost:6379`.

The `application.yml` default of `localhost` works only when the app runs on the host machine with Redis in a container exposing port 6379 to the host.

---

## Q7: What is a multi-stage Dockerfile and should this project use one?

**A:** A multi-stage build separates the build environment (JDK + Maven) from the runtime image (JRE only):

```dockerfile
# Stage 1: Build
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /app
COPY pom.xml .
COPY */pom.xml ./  # copies module POMs for dependency layer caching
RUN mvn dependency:go-offline -q
COPY . .
RUN mvn package -DskipTests -q

# Stage 2: Run
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /app/bootstrap/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

**Advantages:**
- Final image contains only JRE (no Maven, no source code, no build tools) — ~200MB vs ~600MB.
- Build layer caching: `mvn dependency:go-offline` is only re-run when `pom.xml` changes.
- Reduced attack surface.

**This project:** Yes, it should use a multi-stage Dockerfile for CI/CD. Currently there is no `Dockerfile` — the Azure Pipeline produces a JAR, not a container image.

---

## Q8: What is the Docker networking model between containers?

**A:** When Docker Compose starts services, it creates a default bridge network named `{project}_default`. All services in the Compose file join this network.

On this network:
- Services address each other by **service name** (DNS) — `redis` resolves to the Redis container's IP.
- Each container gets its own IP address within the bridge subnet.
- The host machine accesses containers via published ports (`ports:` mapping).
- Containers cannot be reached from outside Docker without published ports.

**Ports in this project:**
- `redis: ports: "6379:6379"` — Redis is accessible from the host at `localhost:6379` for development.
- In production (Kubernetes/ECS), Redis would NOT have a host port mapping — only the app container connects to it via internal service DNS.

---

## Q9: What is the purpose of `volumes: redis-data:/data` vs a bind mount?

**A:**

| Type | Syntax | Data location | Portability |
|---|---|---|---|
| Named volume | `redis-data:/data` | Docker-managed (`/var/lib/docker/volumes/`) | Portable across machines |
| Bind mount | `./data:/data` | Host filesystem at `./data` | Host-specific, easy to inspect |
| Anonymous volume | `/data` | Docker-managed, unnamed | Lost when container is removed |

**Named volume (used here):**
- Survives `docker-compose down` — data persists unless `docker-compose down -v`.
- Docker manages the storage location — no need to create directories on the host.
- Backed up with `docker run --volumes-from` or `docker volume export`.

**Bind mount (common in development):**
- You can open `./data/dump.rdb` directly on your host.
- Risk: host filesystem permissions mismatch with container user.

---

## Q10: What would a production Redis deployment look like vs this development setup?

**A:**

| Aspect | Development (`docker-compose`) | Production |
|---|---|---|
| Availability | Single node — any failure = downtime | Redis Sentinel (HA) or Redis Cluster (sharding + HA) |
| Persistence | AOF on single container | AOF + RDB, replicated to replicas |
| Security | No password, accessible on host port | `requirepass`, TLS (`--tls-port`), ACL rules |
| Network | Exposed to host (`localhost:6379`) | Not exposed to host; accessed only via internal VPN/service mesh |
| Backups | Named volume | Scheduled RDB snapshots to S3/Blob storage |
| Monitoring | None | Redis Exporter → Prometheus → Grafana; alerting on memory/replication lag |
| Connection pooling | Lettuce default (Netty) | Lettuce with `maxPoolSize` tuned for request concurrency |
| Eviction policy | Default `noeviction` | Set `maxmemory-policy allkeys-lru` with `maxmemory` limit |
