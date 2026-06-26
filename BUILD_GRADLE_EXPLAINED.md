# build.gradle Explained

This document explains every block and line of the `build.gradle` file.

---

## Full File at a Glance

```
build.gradle
├── plugins
├── group / version
├── java toolchain
├── repositories
├── ext (extra properties)
├── dependencies
├── test
├── integrationTest (custom task)
├── check
├── jacoco
├── jacocoTestReport
├── checkstyle
├── pmd
├── spotbugs
├── spotbugsMain
├── tasks.withType(JavaCompile) — ErrorProne
└── sonar
```

---

## `plugins`

```groovy
plugins {
    id 'java'
    id 'org.springframework.boot' version '3.3.0'
    id 'io.spring.dependency-management' version '1.1.5'
    id 'org.sonarqube' version '5.1.0.4882'
    id 'jacoco'
    id 'checkstyle'
    id 'pmd'
    id 'com.github.spotbugs' version '6.0.19'
    id 'net.ltgt.errorprone' version '4.0.1'
}
```

Declares the Gradle plugins used in the project. Each plugin adds tasks and configuration to the build.

| Plugin | Purpose |
|---|---|
| `java` | Adds standard Java compilation tasks: `compileJava`, `test`, `jar`, etc. |
| `org.springframework.boot` | Adds the `bootJar` task that packages the app as a runnable fat jar. Also configures Spring Boot's dependency versions. |
| `io.spring.dependency-management` | Works alongside the Spring Boot plugin to manage dependency versions automatically — no need to specify versions for Spring libraries. |
| `org.sonarqube` | Adds the `sonar` task that sends code analysis and coverage data to SonarCloud. |
| `jacoco` | Adds the `jacocoTestReport` task that generates test coverage reports from `.exec` files produced during test runs. |
| `checkstyle` | Adds `checkstyleMain` and `checkstyleTest` tasks that enforce code style rules defined in `config/checkstyle/checkstyle.xml`. |
| `pmd` | Adds `pmdMain` and `pmdTest` tasks that detect bad practices and error-prone patterns using rules in `config/pmd/ruleset.xml`. |
| `com.github.spotbugs` | Adds `spotbugsMain` and `spotbugsTest` tasks that scan compiled bytecode for known bug patterns. |
| `net.ltgt.errorprone` | Integrates Google's ErrorProne compiler into `compileJava` — detects bugs at compile time, no separate task needed. |

---

## `group` and `version`

```groovy
group = 'com.example'
version = '0.0.1-SNAPSHOT'
```

- `group` — the Maven group ID, used to identify the project's organization (typically a reversed domain name).
- `version` — the project version. `SNAPSHOT` means it is a development version, not a stable release.

These are used when naming the output jar: `build/libs/shop-0.0.1-SNAPSHOT.jar`.

---

## Java Toolchain

```groovy
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}
```

Tells Gradle to use **Java 21** to compile and run the project. With toolchain support, Gradle automatically downloads Java 21 if it is not already installed, rather than relying on whatever `JAVA_HOME` is set to on the machine. This ensures the build always uses the correct Java version regardless of the environment.

---

## `repositories`

```groovy
repositories {
    mavenCentral()
}
```

Tells Gradle where to download dependencies from. `mavenCentral()` is the standard Maven Central repository where most Java/Spring libraries are published.

---

## `ext` (Extra Properties)

```groovy
ext {
    mapstructVersion = '1.5.5.Final'
}
```

Defines a reusable variable scoped to the build script. `mapstructVersion` is referenced in the `dependencies` block to keep the MapStruct version consistent between the library and its annotation processor, avoiding version mismatches.

---

## `dependencies`

```groovy
dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-data-mongodb'
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-validation'
    implementation 'org.springframework.boot:spring-boot-starter-cache'
    implementation 'org.springframework.boot:spring-boot-starter-data-redis'
    implementation "org.mapstruct:mapstruct:${mapstructVersion}"

    compileOnly 'org.projectlombok:lombok'

    annotationProcessor 'org.projectlombok:lombok'
    annotationProcessor "org.mapstruct:mapstruct-processor:${mapstructVersion}"

    implementation 'org.springdoc:springdoc-openapi-starter-webmvc-ui:2.6.0'

    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testImplementation 'org.springframework.boot:spring-boot-testcontainers'
    testImplementation 'org.testcontainers:mongodb'
    testImplementation 'org.testcontainers:junit-jupiter'
    testImplementation 'org.apache.httpcomponents.client5:httpclient5'
}
```

Declares all project dependencies grouped by configuration (scope).

### `implementation` — runtime and compile dependencies

| Dependency | Purpose |
|---|---|
| `spring-boot-starter-data-mongodb` | MongoDB driver + Spring Data MongoDB for repository support |
| `spring-boot-starter-web` | Spring MVC for REST controllers, embedded Tomcat server |
| `spring-boot-starter-validation` | Bean Validation (JSR-380) with Hibernate Validator for `@Valid`, `@NotNull`, etc. |
| `spring-boot-starter-cache` | Spring's caching abstraction (`@Cacheable`, `@CacheEvict`) |
| `spring-boot-starter-data-redis` | Redis client + Spring Data Redis for cache implementation |
| `mapstruct` | MapStruct library for compile-time object mapping between DTOs and entities |
| `springdoc-openapi-starter-webmvc-ui` | Auto-generates Swagger UI and OpenAPI docs at `/swagger-ui.html` |

### `compileOnly` — compile time only, not included in the jar

| Dependency | Purpose |
|---|---|
| `lombok` | Generates boilerplate code (`@Getter`, `@Setter`, `@Builder`, etc.) at compile time via annotation processing. Not needed at runtime since the code is already generated. |

### `annotationProcessor` — compile-time code generation

| Dependency | Purpose |
|---|---|
| `lombok` | Runs the Lombok annotation processor during compilation to generate the boilerplate code |
| `mapstruct-processor` | Runs the MapStruct annotation processor during compilation to generate the mapper implementation classes (e.g. `ProductMapperImpl.java`) |

> Both Lombok and MapStruct must be in `annotationProcessor` as well as their respective scopes, otherwise the processors are not invoked during compilation.

### `testImplementation` — test dependencies only, not included in the production jar

| Dependency | Purpose |
|---|---|
| `spring-boot-starter-test` | JUnit 5, Mockito, AssertJ, Spring test utilities (`@SpringBootTest`, `MockMvc`) |
| `spring-boot-testcontainers` | Spring Boot integration for Testcontainers — auto-starts containers when tests run |
| `testcontainers:mongodb` | Testcontainers module that provides a real MongoDB container for integration tests |
| `testcontainers:junit-jupiter` | JUnit 5 extension for Testcontainers (`@Testcontainers`, `@Container`) |
| `httpclient5` | Apache HTTP client used to make real HTTP calls in integration tests |

### `errorprone` — ErrorProne compiler dependency

```groovy
errorprone 'com.google.errorprone:error_prone_core:2.28.0'
```

The `errorprone` configuration is added by the `net.ltgt.errorprone` plugin. It injects ErrorProne's analysis engine into the Java compiler classpath so it runs alongside `javac` during `compileJava`. This is a compile-time-only dependency — it is not included in the final jar.

---

## `test`

```groovy
test {
    useJUnitPlatform()
    exclude '**/*IT.class'
    jvmArgs '-Dnet.bytebuddy.experimental=true'
    environment 'TESTCONTAINERS_RYUK_DISABLED', 'true'
}
```

Configures the built-in `test` task (unit tests).

| Line | Purpose |
|---|---|
| `useJUnitPlatform()` | Tells Gradle to use the JUnit 5 platform to discover and run tests |
| `exclude '**/*IT.class'` | Skips any compiled class whose name ends in `IT` — these are reserved for the `integrationTest` task |
| `jvmArgs '-Dnet.bytebuddy.experimental=true'` | Enables Byte Buddy's experimental mode, required for Mockito to work correctly with Java 21's newer class formats |
| `environment 'TESTCONTAINERS_RYUK_DISABLED', 'true'` | Disables Testcontainers' Ryuk container (a cleanup sidecar). Ryuk can cause issues in certain Docker environments so it is disabled globally |

---

## `integrationTest` (Custom Task)

```groovy
tasks.register('integrationTest', Test) {
    useJUnitPlatform()
    include '**/*IT.class'
    jvmArgs '-Dnet.bytebuddy.experimental=true'
    environment 'TESTCONTAINERS_RYUK_DISABLED', 'true'
    def proxySocket = '/tmp/docker-tc-proxy.sock'
    if (new File(proxySocket).exists()) {
        environment 'TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE', proxySocket
    }
    testLogging {
        events 'passed', 'failed', 'skipped'
        showStandardStreams false
    }
}
```

Registers a new Gradle task named `integrationTest` of type `Test` (same underlying type as the built-in `test` task).

| Line | Purpose |
|---|---|
| `tasks.register('integrationTest', Test)` | Creates a new task that behaves like a test runner |
| `useJUnitPlatform()` | Uses JUnit 5, same as unit tests |
| `include '**/*IT.class'` | Only runs classes whose name ends in `IT` (e.g. `ProductServiceIT`) |
| `jvmArgs '-Dnet.bytebuddy.experimental=true'` | Same Byte Buddy fix as unit tests |
| `environment 'TESTCONTAINERS_RYUK_DISABLED', 'true'` | Same Ryuk fix as unit tests |
| `def proxySocket = '/tmp/docker-tc-proxy.sock'` | Defines the path to a Testcontainers proxy socket used when running tests inside a Docker container in Jenkins |
| `if (new File(proxySocket).exists()) { ... }` | If the proxy socket file exists (i.e. the test is running inside Jenkins' Docker agent), redirects Testcontainers to use that socket instead of the default Docker socket |
| `testLogging { events 'passed', 'failed', 'skipped' }` | Prints each test result (`PASSED`, `FAILED`, `SKIPPED`) to the console output during the build |
| `showStandardStreams false` | Suppresses `System.out` and `System.err` output from tests in the console to keep logs clean |

---

## `check.dependsOn integrationTest`

```groovy
check.dependsOn integrationTest
```

The `check` task is Gradle's standard verification task (run by `./gradlew check`). By default it only runs unit tests. This line adds `integrationTest` as a dependency so that running `./gradlew check` also runs integration tests. This is useful for CI environments that use `check` as the standard verification command.

---

## `jacoco`

```groovy
jacoco {
    toolVersion = '0.8.12'
}
```

Configures the JaCoCo plugin. `toolVersion` pins the exact version of the JaCoCo agent used to instrument the bytecode and collect coverage data during test execution. Pinning the version prevents unexpected behavior from automatic upgrades.

---

## `jacocoTestReport`

```groovy
jacocoTestReport {
    dependsOn test, integrationTest
    executionData fileTree(project.buildDir).include('jacoco/*.exec')
    classDirectories.setFrom(files(classDirectories.files.collect {
        fileTree(dir: it, exclude: [
            '**/dto/**',
            '**/entity/**',
            '**/model/**',
            '**/mapper/**',
            '**/config/**',
        ])
    }))
    reports {
        xml.required  = true
        html.required = true
    }
}
```

Configures the `jacocoTestReport` task that generates the coverage report.

| Line | Purpose |
|---|---|
| `dependsOn test, integrationTest` | Ensures both test tasks run before generating the report, so coverage data from both is available |
| `executionData fileTree(...).include('jacoco/*.exec')` | Collects all `.exec` files from `build/jacoco/`. Each test task produces its own `.exec` file with raw coverage data. This merges them into a single report. |
| `classDirectories.setFrom(...)` | Defines which compiled classes to measure coverage for. By default all classes are included — this block overrides that to exclude certain packages. |
| `exclude '**/dto/**'` | Excludes DTOs — no business logic to measure |
| `exclude '**/entity/**'` | Excludes JPA/MongoDB entity classes — no business logic |
| `exclude '**/model/**'` | Excludes domain model classes — no business logic |
| `exclude '**/mapper/**'` | Excludes MapStruct mappers — generated code, not worth measuring |
| `exclude '**/config/**'` | Excludes Spring configuration classes — boilerplate wiring |
| `xml.required = true` | Generates an XML report consumed by SonarCloud for quality gate analysis |
| `html.required = true` | Generates an interactive HTML report published to Jenkins for visual inspection |

---

## `checkstyle`

```groovy
checkstyle {
    toolVersion = '10.17.0'
    configFile = file('config/checkstyle/checkstyle.xml')
    ignoreFailures = false
    maxWarnings = 0
}
```

Configures the Checkstyle plugin.

| Line | Purpose |
|---|---|
| `toolVersion = '10.17.0'` | Pins the Checkstyle engine version to avoid behavior changes from automatic upgrades |
| `configFile = file('config/checkstyle/checkstyle.xml')` | Points to the project's custom rule set. Without this, Checkstyle would use a default (or no) config. |
| `ignoreFailures = false` | Causes the build to fail when violations are found. Setting this to `true` would generate reports but not break the build. |
| `maxWarnings = 0` | Treats any warning as a build failure — zero tolerance for style issues |

The config file at `config/checkstyle/checkstyle.xml` enforces rules including: no star imports, no unused imports, naming conventions (camelCase methods, UPPER_CASE constants), empty catch block detection, and `equals()`/`hashCode()` pairing.

---

## `pmd`

```groovy
pmd {
    toolVersion = '7.5.0'
    ignoreFailures = false
    ruleSets = []
    ruleSetFiles = files('config/pmd/ruleset.xml')
}
```

Configures the PMD plugin.

| Line | Purpose |
|---|---|
| `toolVersion = '7.5.0'` | Pins the PMD version |
| `ignoreFailures = false` | Fails the build on any violation |
| `ruleSets = []` | Clears PMD's default built-in rule sets so only the project's custom set is used. Without this, PMD applies its own defaults in addition to the custom file. |
| `ruleSetFiles = files('config/pmd/ruleset.xml')` | Points to the project's custom rule set |

The config file at `config/pmd/ruleset.xml` includes `bestpractices` and `errorprone` rule categories with suppressions for rules that produce false positives in this project (e.g. `LooseCoupling` for MongoDB `Document`, `AvoidDuplicateLiterals` for annotation strings).

---

## `spotbugs`

```groovy
spotbugs {
    toolVersion = '4.8.6'
    ignoreFailures = false
    excludeFilter = file('config/spotbugs/exclude.xml')
}
```

Configures the SpotBugs plugin.

| Line | Purpose |
|---|---|
| `toolVersion = '4.8.6'` | Pins the SpotBugs engine version |
| `ignoreFailures = false` | Fails the build when bugs are found |
| `excludeFilter = file('config/spotbugs/exclude.xml')` | Points to an XML filter file that suppresses known false positives |

The exclude filter at `config/spotbugs/exclude.xml` suppresses: MapStruct-generated `*MapperImpl` classes, the Spring Boot `*Application` class, and the `EI_EXPOSE_REP`/`EI_EXPOSE_REP2` patterns (Lombok `@Data` getters returning mutable collection references — expected in Spring Boot DTOs).

---

## `spotbugsMain`

```groovy
spotbugsMain {
    reports {
        html {
            required = true
            stylesheet = 'fancy-hist.xsl'
        }
        xml {
            required = false
        }
    }
}
```

Configures the report output for the `spotbugsMain` task specifically (SpotBugs creates separate tasks per source set).

| Line | Purpose |
|---|---|
| `html { required = true }` | Generates an HTML report published to Jenkins for visual inspection |
| `stylesheet = 'fancy-hist.xsl'` | Uses SpotBugs' built-in fancy stylesheet for a readable HTML layout |
| `xml { required = false }` | Disables XML output — XML is only needed if consuming the report programmatically (e.g. SonarQube). HTML is sufficient here. |

The report is written to `build/reports/spotbugs/main.html`.

---

## `tasks.withType(JavaCompile)` — ErrorProne

```groovy
tasks.withType(JavaCompile).configureEach {
    options.fork = true
    options.errorprone {
        enabled = true
        disableWarningsInGeneratedCode = true
    }
}
```

Configures ErrorProne for every compilation task (`compileJava`, `compileTestJava`, etc.).

| Line | Purpose |
|---|---|
| `tasks.withType(JavaCompile).configureEach` | Applies the configuration to all Java compile tasks, not just `compileJava` |
| `options.fork = true` | Runs the compiler in a forked JVM process. Required by ErrorProne — it must control the compiler's classpath and cannot run in-process with Gradle's JVM. |
| `options.errorprone { enabled = true }` | Activates ErrorProne's analysis engine. Without this, the plugin is present but does nothing. |
| `disableWarningsInGeneratedCode = true` | Suppresses ErrorProne warnings in generated files (MapStruct's `*MapperImpl`, Lombok's generated methods). Only hand-written code is checked. |

ErrorProne runs as part of normal compilation — there is no separate Gradle task. Violations appear as compiler warnings or errors in the build output. Unlike Checkstyle/PMD/SpotBugs, ErrorProne catches bugs that would only surface at runtime (e.g. ignoring return values, using `LocalDateTime.now()` without an explicit timezone).

---

## `sonar`

```groovy
sonar {
    properties {
        property 'sonar.projectKey',   'nelsonvillam_shop'
        property 'sonar.organization', 'nelsonvillam'
        property 'sonar.coverage.jacoco.xmlReportPaths', 'build/reports/jacoco/test/jacocoTestReport.xml'
    }
}
```

Configures the SonarQube/SonarCloud analysis.

| Property | Purpose |
|---|---|
| `sonar.projectKey` | Unique identifier for the project in SonarCloud. Must match the key created on the SonarCloud dashboard. |
| `sonar.organization` | The SonarCloud organization name associated with your account. |
| `sonar.coverage.jacoco.xmlReportPaths` | Tells SonarCloud where to find the JaCoCo XML coverage report so it can calculate coverage metrics and apply quality gate rules. |

> The SonarCloud authentication token is not stored here — it is injected at runtime by the `withSonarQubeEnv('sonarqube')` block in the Jenkinsfile, which reads it from Jenkins' credential store.
