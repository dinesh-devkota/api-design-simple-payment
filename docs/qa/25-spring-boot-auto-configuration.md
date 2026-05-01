# Q&A 25 — Spring Boot Auto-Configuration Internals

> How Spring Boot wires itself — the magic behind `@SpringBootApplication`, `META-INF/spring.factories`, `AutoConfiguration.imports`, and conditional beans.

---

## Q1: What exactly does `@SpringBootApplication` expand to?

**A:** It is a composed annotation equivalent to:

```java
@SpringBootConfiguration   // = @Configuration — marks this class as a config source
@EnableAutoConfiguration   // tells Spring Boot to read AUTO_CONFIGURATION imports
@ComponentScan             // scans the package and sub-packages for @Component, @Service, etc.
```

When you place `@SpringBootApplication` at `com.customercare`, the component scan covers `com.customercare.**` — which includes all four Maven modules since they all live under that root package.

---

## Q2: What is `@EnableAutoConfiguration` actually doing?

**A:** It imports `AutoConfigurationImportSelector`. On startup this selector:

1. Reads all JAR files on the classpath for `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` (Spring Boot 2.7+) or the older `META-INF/spring.factories` key `org.springframework.boot.autoconfigure.EnableAutoConfiguration`.
2. Loads each listed auto-configuration class (e.g., `RedisAutoConfiguration`, `DataSourceAutoConfiguration`, `JacksonAutoConfiguration`).
3. Evaluates `@Conditional*` annotations on each — only the ones whose conditions pass are registered.
4. Registers the remaining beans into the `ApplicationContext`.

**Why does this matter?**
Adding a dependency to `pom.xml` (e.g., `spring-boot-starter-data-redis`) brings in a JAR whose `AutoConfiguration.imports` file lists `RedisAutoConfiguration`. Spring Boot finds and registers it without any `@Import` in your code. Remove the dependency → `RedisAutoConfiguration` disappears from the classpath → no Redis beans.

---

## Q3: How does Spring Boot know which `DataSource` to create without any XML?

**A:** `DataSourceAutoConfiguration` reads properties from `spring.datasource.*`. It applies conditions:
- `@ConditionalOnClass(DataSource.class)` — only if a JDBC driver is on the classpath.
- `@ConditionalOnMissingBean(DataSource.class)` — does not create a DataSource if you already declared one.

The `EmbeddedDatabaseCondition` checks if an embedded DB (H2, HSQL, Derby) is present — if so, it creates an in-memory DataSource automatically with zero config.

---

## Q4: What is the difference between `@Configuration` and `@SpringBootConfiguration`?

**A:** `@SpringBootConfiguration` is a specialisation of `@Configuration` that marks the class as the "primary" Spring Boot configuration class. The key functional difference: only ONE class annotated with `@SpringBootConfiguration` should exist in the test's `@SpringBootTest` search path, because `@SpringBootTest` walks up the package hierarchy looking for the `@SpringBootConfiguration` class to load the application context. Having two in the same scan path causes `IllegalStateException`.

---

## Q5: What does `spring-boot-starter-parent` give you beyond dependency version management?

**A:**

| Feature | Provided by |
|---|---|
| Dependency versions (the "BOM") | `spring-boot-dependencies` (imported transitively) |
| Plugin versions (compiler, surefire, failsafe) | `spring-boot-starter-parent` |
| Java compiler source/target | `maven.compiler.source` = 17 (override to 21) |
| UTF-8 resource filtering | Yes |
| `repackage` goal for fat JAR | `spring-boot-maven-plugin` pre-configured |
| Test resource filtering | `src/test/resources` auto-included |
| `application*.yml` profile activation | `spring.profiles.active` property handling |

If you cannot extend `spring-boot-starter-parent` (e.g., your organisation has its own parent POM), you import `spring-boot-dependencies` as a BOM in `<dependencyManagement>` with `<scope>import</scope><type>pom</type>` — you lose the plugin management but keep the version alignment.

---

## Q6: What is `@ConditionalOnClass` vs `@ConditionalOnBean` vs `@ConditionalOnProperty`?

**A:**

| Annotation | Condition type | Evaluated on |
|---|---|---|
| `@ConditionalOnClass` | Classpath presence | Is this class loadable? |
| `@ConditionalOnMissingClass` | Classpath absence | Is this class NOT loadable? |
| `@ConditionalOnBean` | Bean existence | Is a bean of this type already registered? |
| `@ConditionalOnMissingBean` | Bean absence | Is no bean of this type registered yet? |
| `@ConditionalOnProperty` | Property value | Does this property key exist with this value? |
| `@ConditionalOnExpression` | SpEL expression | Evaluates arbitrary Spring Expression Language |
| `@ConditionalOnResource` | Resource existence | Is this classpath/filesystem resource present? |
| `@ConditionalOnWebApplication` | Web context type | Is this a servlet or reactive web application? |

**Gotcha:** `@ConditionalOnBean` and `@ConditionalOnMissingBean` are order-sensitive. They check the state of the bean registry *at the time this `@Configuration` class is processed*. If processed too early, the bean might not be registered yet even though it will be registered later. This is a common source of subtle auto-configuration bugs. The fix is to use `@AutoConfigureAfter` to declare ordering.

---

## Q7: How does Spring Boot load `application.yml` vs `application-{profile}.yml`?

**A:** Spring Boot uses `ConfigDataEnvironmentPostProcessor` to load property sources in priority order:

1. Command-line arguments (`--server.port=9090`)
2. `SPRING_APPLICATION_JSON` environment variable
3. Java system properties (`-Dserver.port=9090`)
4. OS environment variables (`SERVER_PORT=9090`)
5. `application-{profile}.yml` (profile-specific, overrides base)
6. `application.yml` (base config)
7. `@PropertySource` annotations
8. Default property values

Profile-specific files override base file properties for the same key. Profiles are activated via `spring.profiles.active` (property or env var).

**In this project:** `application.yml` sets `app.fixed-date=2026-04-01`. In production, you would set this property to empty or not set it — relying on `ClockConfig`'s `@ConditionalOnMissingBean` to provide the real system clock.

---

## Q8: What happens if you put `@SpringBootApplication` in the wrong package?

**A:** The component scan covers only `com.customercare.*` and sub-packages. If `@SpringBootApplication` is in `com.payments` instead:

- All `@Service`, `@Repository`, `@Component` beans in `com.customercare.*` are **not scanned** — `NoSuchBeanDefinitionException` at startup.
- You must add `@SpringBootApplication(scanBasePackages = {"com.customercare", "com.payments"})` to fix it.

**This is exactly why** the project places `@SpringBootApplication` at `com.customercare` (the common root of all four modules) — a single scan covers everything.

---

## Q9: What is the difference between a `@Bean` method in a `@Configuration` class vs a `@Component` class?

**A:**

| | `@Configuration` + `@Bean` | `@Component` + `@Bean` |
|---|---|---|
| Class is proxied by CGLIB? | **Yes** — calls to `@Bean` methods are intercepted | **No** — plain class |
| Cross-method bean calls | Returns the **same singleton** — `beanA()` calling `beanB()` returns the container's `beanB` | Returns **new instance** on each call — breaks singleton contract |
| Performance | Slightly higher startup cost (CGLIB proxy generation) | Lower overhead |
| Use case | Configuration classes with inter-dependent beans | Simple factories, utility beans |

**In this project:** `ClockConfig`, `RedisConfig` should be `@Configuration` (they define infrastructure beans that may depend on each other). This is not `@Component`.

---

## Q10: What is Spring Boot's startup sequence in order?

**A:**

1. `SpringApplication.run()` is called.
2. `ApplicationContextInitializer` beans run — this is where `RedisTestInitializer` starts embedded Redis.
3. The `Environment` is prepared — property sources loaded (YAML, env vars, system props).
4. `ApplicationListener<EnvironmentPreparedEvent>` beans fire.
5. `BeanDefinitionLoader` scans for `@Component`, `@Configuration`, `@Bean` — registers bean definitions (not yet instantiated).
6. Auto-configuration is applied — `AutoConfigurationImportSelector` reads `AutoConfiguration.imports`.
7. `BeanFactoryPostProcessor` runs — modifies bean definitions before instantiation.
8. Singletons are instantiated and wired — `@Autowired` dependencies injected, `@PostConstruct` methods called.
9. `ApplicationListener<ContextRefreshedEvent>` fires.
10. `ApplicationRunner` / `CommandLineRunner` beans run.
11. `ApplicationListener<ApplicationStartedEvent>` fires.
12. Application is ready — `ApplicationListener<ApplicationReadyEvent>` fires.

**Why does this matter?** `@Value` injection happens in step 8 — you cannot use `@Value` fields in constructors of beans instantiated in step 7 unless you use constructor injection (preferred). `@PostConstruct` is safe for post-injection initialisation because it runs after step 8.
