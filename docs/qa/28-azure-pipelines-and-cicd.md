# Q&A 28 — Azure Pipelines & CI/CD for Java

> Everything behind `azure-pipelines.yml` — agents, stages, caching, artifact publishing, and what a production pipeline looks like.

---

## Q1: What does the `azure-pipelines.yml` in this project do step by step?

**A:** The pipeline:

1. **Trigger** — runs on every push to `main` (and potentially PRs if PR triggers are configured).
2. **Agent pool** — `ubuntu-latest` Microsoft-hosted agent (fresh VM per run, ~4 vCPU, 16GB RAM).
3. **JDK setup** — `JavaToolInstaller` task sets Java 21 (Temurin distribution).
4. **Maven verify** — `mvn verify` runs compile + unit tests + integration tests for all 4 modules.
5. **Publish test results** — the `PublishTestResults` task reads `**/surefire-reports/*.xml` and shows pass/fail counts in the pipeline UI.
6. **Publish JAR artifact** — `PublishBuildArtifacts` uploads the fat JAR from `bootstrap/target/` as a pipeline artifact downloadable from the run.

---

## Q2: What is the difference between `mvn test`, `mvn verify`, and `mvn package`?

**A:**

| Command | Phases executed | What runs |
|---|---|---|
| `mvn test` | validate → compile → test | Unit tests (Surefire plugin) |
| `mvn package` | + package | Creates JAR/WAR — skips integration tests |
| `mvn verify` | + integration-test → verify | Runs integration tests (Failsafe plugin) + post-integration-test cleanup |
| `mvn install` | + install | Installs artifact to local `~/.m2` — unnecessary in CI |
| `mvn deploy` | + deploy | Uploads artifact to remote repository (Nexus, Azure Artifacts) |

**In CI:** `mvn verify` is the correct command — runs everything including integration tests, without installing to local repo. `mvn install` in CI pollutes the agent's Maven cache with the project's own artifacts.

---

## Q3: Why does the pipeline use `mvn verify` instead of `mvn test`?

**A:** `mvn test` only runs tests bound to the `test` phase — the Surefire plugin. `PaymentControllerIntegrationTest` is an integration test that requires embedded Redis startup. In Maven convention:

- Tests in `**/*Test.java` (or `**/*Tests.java`) → Surefire → `test` phase.
- Tests in `**/*IT.java` (or `**/*IntegrationTest.java`) → Failsafe → `integration-test` phase.

If the project uses Maven Failsafe for integration tests (`*IntegrationTest.java`), `mvn test` would skip them. `mvn verify` runs both.

**Note:** In this project, `PaymentControllerIntegrationTest` might actually be picked up by Surefire if the Failsafe plugin is not explicitly configured — `mvn verify` covers both cases.

---

## Q4: What is the risk of not caching the Maven repository in the pipeline?

**A:** Without caching:
- Every pipeline run downloads all dependencies from Maven Central: ~200-500MB of JARs.
- On a slow connection or rate-limited registry: 3-5 minutes just for downloads.
- Increases flakiness (network failures cause build failures).

**With caching (`Cache@2` task):**
```yaml
- task: Cache@2
  inputs:
    key: 'maven | "$(Agent.OS)" | **/pom.xml'
    restoreKeys: 'maven | "$(Agent.OS)"'
    path: $(HOME)/.m2/repository
```

The key includes the OS and all `pom.xml` file hashes — cache is invalidated whenever any POM changes. On cache hit, downloads are skipped entirely.

---

## Q5: What is a pipeline artifact vs a release artifact?

**A:**

| Type | Tool | Lifetime | Purpose |
|---|---|---|---|
| Pipeline artifact | `PublishBuildArtifacts` | Duration of pipeline run (30 days default) | Share files between stages, download for debugging |
| Release artifact | Azure Artifacts / Nexus / GitHub Packages | Permanent (versioned) | Deploy to environments, version history |

**This project** publishes a pipeline artifact (the fat JAR). A production setup would additionally:
1. Determine the version (from `pom.xml` or a Git tag).
2. Push the JAR to Azure Artifacts (`mvn deploy`) or build and push a Docker image to Azure Container Registry.
3. Trigger a release pipeline to deploy to staging/production.

---

## Q6: What is the difference between a Microsoft-hosted agent and a self-hosted agent?

**A:**

| | Microsoft-hosted | Self-hosted |
|---|---|---|
| Infrastructure | Microsoft manages the VM | You manage the machine/container |
| Cost | Included (limited parallel jobs in free tier) | Machine cost only |
| Freshness | Fresh VM every run — no state pollution | Persistent — can cache Maven repo permanently |
| Software | Pre-installed tools (JDK, Docker, Python) | You install and maintain tools |
| Network access | Public internet only | Can reach private VPNs, on-premise systems |
| Performance | Consistent baseline | As fast as your hardware |
| Use case | Most CI builds | Builds needing private network access or heavy caching |

**For this project:** Microsoft-hosted `ubuntu-latest` is fine — the project has no private network dependencies.

---

## Q7: What is a branch strategy and how should this pipeline support it?

**A:** Common strategies:

**Trunk-based development (recommended for this project's size):**
- All developers push to `main` or short-lived feature branches.
- Pipeline runs on every push.
- Merges to `main` trigger deployment.

**Gitflow:**
- `develop`, `feature/*`, `release/*`, `hotfix/*` branches.
- More process overhead — suitable for larger teams with formal release cycles.

**Azure Pipelines config for branch protection:**
```yaml
trigger:
  branches:
    include:
      - main
      - feature/*
pr:
  branches:
    include:
      - main
```

`pr:` trigger runs the pipeline on pull requests targeting `main` — tests must pass before merge. This is the minimum required for maintainability.

---

## Q8: What is a multi-stage pipeline and why would this project benefit from one?

**A:** A multi-stage pipeline splits CI into logical phases that can run on different agents, with approvals between them:

```yaml
stages:
  - stage: Build
    jobs:
      - job: Build
        steps:
          - script: mvn verify
          - task: PublishBuildArtifacts

  - stage: DeployStaging
    dependsOn: Build
    condition: succeeded()
    jobs:
      - deployment: DeployToStaging
        environment: staging
        steps:
          - script: deploy.sh staging

  - stage: DeployProduction
    dependsOn: DeployStaging
    jobs:
      - deployment: DeployToProd
        environment: production     # environments can have approval gates
        steps:
          - script: deploy.sh production
```

**Benefits:**
- Gate on test pass before any deployment.
- Manual approval required before production.
- Each stage has its own logs, status, and rollback capability.
- Parallel stages (e.g., deploy to multiple regions simultaneously).

---

## Q9: How would you add a security scan to the pipeline?

**A:**

1. **Dependency vulnerability scan (OWASP Dependency Check):**
```yaml
- script: mvn org.owasp:dependency-check-maven:check -DfailBuildOnCVSS=7
```
Fails the build if any dependency has a CVSS score ≥ 7.

2. **Docker image scan (Trivy):**
```yaml
- script: |
    docker build -t myapp:$(Build.BuildId) .
    trivy image --exit-code 1 --severity HIGH,CRITICAL myapp:$(Build.BuildId)
```

3. **SAST (Static Application Security Testing):**
- SonarQube / SonarCloud analysis: `mvn sonar:sonar`
- GitHub Advanced Security (CodeQL) — not available in Azure DevOps directly.
- Checkmarx / Veracode plugins for Azure Pipelines.

4. **Secrets detection:**
```yaml
- script: |
    pip install detect-secrets
    detect-secrets scan --all-files
```
Prevents accidental credential commits.

---

## Q10: The pipeline publishes a JAR but the team wants to deploy to Kubernetes. What changes?

**A:** Shift from "publish JAR" to "publish Docker image":

1. Add a `Dockerfile` (multi-stage — see Q&A 27).
2. In the pipeline, after `mvn verify`:
```yaml
- task: Docker@2
  inputs:
    command: buildAndPush
    repository: myregistry.azurecr.io/customer-care-api
    dockerfile: Dockerfile
    tags: |
      $(Build.BuildId)
      latest
```
3. Add a Kubernetes deployment stage:
```yaml
- task: KubernetesManifest@0
  inputs:
    action: deploy
    manifests: k8s/deployment.yaml
    containers: myregistry.azurecr.io/customer-care-api:$(Build.BuildId)
```
4. Create `k8s/deployment.yaml` with `Deployment`, `Service`, and `ConfigMap` for environment variables.
5. Set Redis connection (`SPRING_DATA_REDIS_HOST`) to a Kubernetes Service name pointing to a managed Redis instance (Azure Cache for Redis, not a Docker Compose container).
