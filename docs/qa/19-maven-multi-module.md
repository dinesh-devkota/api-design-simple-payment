# Q&A 19 — Maven Multi-Module Build, `pom.xml`, and Dependency Management

> How Maven wires four modules together, what each `pom.xml` section does, and what goes wrong when you get it wrong.

---

## Q1: What is a Maven multi-module project and why use one?

**A:** A multi-module Maven project defines a **parent POM** that lists child modules. Maven builds them in the correct dependency order. This project has:

```
customer-care-api (parent pom.xml, packaging: pom)
├── domain        (packaging: jar)
├── infra         (packaging: jar — depends on domain)
├── app           (packaging: jar — depends on domain)
└── bootstrap     (packaging: jar — depends on domain, infra, app)
```

**Why multi-module?**
1. **Compile-time layer enforcement** — `domain/pom.xml` lists no sibling-module dependencies, making it impossible for domain code to import `infra` classes without a build failure.
2. **Independent versioning** — each module can be released as a separate JAR (useful if `domain` becomes a shared library).
3. **Incremental builds** — Maven only recompiles modules whose source has changed since the last build.

---

## Q2: What is `<packaging>pom</packaging>` on the parent and why can't it produce a JAR?

**A:** `pom` packaging means "this artifact is only a POM file — it contains configuration and module declarations, not Java source code." Maven will not try to compile it or produce a JAR.

The parent POM's job is to:
- Declare `<modules>` — the list of child modules to build.
- Set shared `<properties>` (e.g., `<java.version>21</java.version>`).
- Manage shared `<dependencyManagement>` — central version declarations that child modules inherit without repeating version numbers.
- Configure shared `<pluginManagement>` — annotation processor ordering for Lombok and MapStruct.

If you accidentally set `<packaging>jar</packaging>` on the parent, Maven tries to compile it as a Java project, finds no `src/main/java`, and either fails or produces an empty JAR.

---

## Q3: What is `<dependencyManagement>` and how does it differ from `<dependencies>`?

**A:**

| Section | Effect |
|---|---|
| `<dependencyManagement>` | Declares versions **centrally** but does NOT add the dependency to any module's classpath |
| `<dependencies>` | Actually adds the dependency to the current module's classpath |

In the parent `pom.xml`:
```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>com.customercare</groupId>
            <artifactId>domain</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>org.mapstruct</groupId>
            <artifactId>mapstruct</artifactId>
            <version>${mapstruct.version}</version>
        </dependency>
    </dependencies>
</dependencyManagement>
```

This says: "if any child module wants these artifacts, use these versions." The child modules still must declare `<dependency>` in their own `pom.xml` to actually use them — but they omit the `<version>` tag (it is inherited from the parent's `<dependencyManagement>`).

**Why this matters:** Without `<dependencyManagement>`, each child module would hard-code version strings. Updating MapStruct from `1.5.5.Final` to `1.6.0.Final` would require editing every child `pom.xml`. With `<dependencyManagement>`, it's a one-line change in the parent.

---

## Q4: What is `${project.version}` and why is it used for inter-module dependencies?

**A:** `${project.version}` is a Maven built-in property that resolves to the current project's version (`1.0.0-SNAPSHOT` here). Using it for inter-module dependencies ensures all modules are always on the same version — you can't accidentally have `bootstrap` depending on `domain:1.0.0` while the rest are on `1.1.0-SNAPSHOT`.

When you run `mvn versions:set -DnewVersion=2.0.0`, Maven updates all `<version>` tags in the parent and all modules in one command. `${project.version}` ensures inter-module references update automatically.

---

## Q5: What does `SNAPSHOT` in the version mean?

**A:** A `SNAPSHOT` version (`1.0.0-SNAPSHOT`) signals a **work in progress** — not a released, stable artifact. Maven's behaviour differs for snapshots:
- Maven checks for an updated `SNAPSHOT` JAR in the remote repository on every build (by default, once per day).
- Two developers on different machines can have different `1.0.0-SNAPSHOT` JARs (the latest snapshot overwrites the previous).
- Snapshots should never be used as a dependency in a released artifact.

When the service is ready to release, the version becomes `1.0.0` (no `-SNAPSHOT`), indicating an immutable, stable artifact.

---

## Q6: What does the `<annotationProcessorPaths>` configuration do and why is the order important?

**A:**
```xml
<annotationProcessorPaths>
    <path>lombok</path>                     <!-- 1st -->
    <path>lombok-mapstruct-binding</path>   <!-- 2nd -->
    <path>mapstruct-processor</path>        <!-- 3rd -->
</annotationProcessorPaths>
```

Annotation processors run during `javac`'s compilation phase. They run in the order declared. The ordering here is critical:

1. **Lombok runs first** — it generates getters, setters, and constructors from `@Data`, `@Builder`, etc.
2. **`lombok-mapstruct-binding` runs second** — this is a coordination shim that ensures Lombok's generated code is visible to MapStruct.
3. **MapStruct runs third** — it reads the Lombok-generated getters/setters to generate mapper implementations.

If Lombok ran *after* MapStruct, MapStruct would see a class with no getters and generate an empty mapper (`toResponse()` would return an empty DTO with all null fields). The bug would only appear at runtime — no compile error.

---

## Q7: What is `spring-boot-starter-parent` and what does it provide?

**A:**
```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.2.5</version>
</parent>
```

`spring-boot-starter-parent` is Spring Boot's opinionated parent POM. It provides:
- **Dependency management** for hundreds of libraries with tested, compatible version combinations (Jackson, Logback, JUnit 5, AssertJ, Mockito, Lettuce, Spring Data Redis, etc.).
- **Plugin configuration** for `maven-compiler-plugin` (Java version), `maven-surefire-plugin` (test runner), `spring-boot-maven-plugin` (fat JAR packaging).
- **Resource filtering** — `${...}` in `application.yml` are resolved against Maven properties.
- **Default profiles** — the `local`, `test`, and `prod` profiles are not Spring Boot-specific but the parent enables profile-based resource filtering.

Without the parent, you'd manage all these versions manually — and version incompatibilities between Spring Boot, Jackson, and Lettuce are notoriously difficult to debug.

---

## Q8: What does `mvn verify` do vs `mvn test` vs `mvn package`?

**A:** Maven's lifecycle phases run in order. Each phase includes all previous phases:

| Command | Phases executed | What happens |
|---|---|---|
| `mvn compile` | validate → compile | Compiles source code (including annotation processing) |
| `mvn test` | ...+ test-compile → test | Runs unit tests (`surefire` plugin, `src/test/java`) |
| `mvn package` | ...+ package | Creates the JAR (but runs unit tests, not integration tests) |
| `mvn verify` | ...+ integration-test → verify | Runs **all** tests including integration tests (`failsafe` plugin) |
| `mvn install` | ...+ install | Installs the JAR into the local `~/.m2` repository |

The CI pipeline uses `mvn verify` to run everything and verify correctness before publishing the artifact.

---

## Q9: How does Maven resolve which module to run `spring-boot:run` on?

**A:**
```bash
mvn spring-boot:run -pl bootstrap
```

`-pl bootstrap` means "only build the `bootstrap` module." Maven still resolves its transitive dependencies (domain, app, infra) from the local Maven repository (or builds them if needed). `spring-boot-maven-plugin` in `bootstrap/pom.xml` starts the Spring Boot application.

Without `-pl bootstrap`, Maven would try to run `spring-boot:run` on all four modules — failing on `domain`, `infra`, and `app` because they have no `SpringApplication.run()` entry point and no `spring-boot-maven-plugin`.

---

## Q10: What is the dependency graph between the four modules?

**A:**

```
bootstrap
  ├── domain    (domain logic, SPI interfaces, exceptions)
  ├── app       (REST controllers, mappers, exception handler)
  │    └── domain
  └── infra     (Redis adapters, entity, config)
       └── domain

domain  — no inter-module dependencies
```

The key invariant: **`domain` depends on nothing**. `app` and `infra` both depend on `domain`. `bootstrap` depends on all three and wires them together.

If you accidentally add a dependency from `domain` to `infra` or `app` in `domain/pom.xml`, Maven will create a circular dependency error at build time:
```
[ERROR] The projects in the reactor contain a cyclic reference
```

This is one of the strongest structural enforcement mechanisms in the project.

---

## Q11: What is the difference between `<scope>test</scope>` and `<scope>compile</scope>`?

**A:**

| Scope | Available in main code? | Available in test code? | Included in JAR? |
|---|---|---|---|
| `compile` (default) | Yes | Yes | Yes |
| `test` | No | Yes | No |
| `provided` | Yes | Yes | No (provided by container) |
| `runtime` | No | Yes | Yes |

Example: `embedded-redis` is `<scope>test</scope>` — it is only on the classpath during tests, never in the production JAR. If it were `<scope>compile</scope>`, the production JAR would include the embedded Redis binary, adding unnecessary weight and startup complexity.
