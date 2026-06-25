# Maven vs Gradle — Project Comparison

## Build Files

| | Maven | Gradle |
|---|---|---|
| Build file | `pom.xml` | `build.gradle` |
| Project name | `<artifactId>` in `pom.xml` | `settings.gradle` |
| Wrapper | `mvnw` / `mvnw.cmd` | `gradlew` / `gradlew.bat` |

---

## Project Identity

**Maven (`pom.xml`)**
```xml
<groupId>com.example</groupId>
<artifactId>shop</artifactId>
<version>0.0.1-SNAPSHOT</version>
```

**Gradle (`build.gradle`)**
```groovy
group = 'com.example'
version = '0.0.1-SNAPSHOT'
// name comes from settings.gradle → rootProject.name = 'shop'
```

---

## Spring Boot Plugin

**Maven** inherits everything from the parent BOM:
```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.3.0</version>
</parent>
```

**Gradle** applies two plugins — one to package the app, one to manage dependency versions:
```groovy
id 'org.springframework.boot' version '3.3.0'
id 'io.spring.dependency-management' version '1.1.5'
```

---

## Java Version

**Maven**
```xml
<properties>
    <java.version>21</java.version>
</properties>
```

**Gradle**
```groovy
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}
```

---

## Dependencies

Dependency scopes map differently between the two tools.

| Maven scope | Gradle config | When to use |
|---|---|---|
| `compile` (default) | `implementation` | Runtime + compile |
| `<optional>true</optional>` | `compileOnly` | Compile only, not packaged |
| `<scope>test</scope>` | `testImplementation` | Tests only |
| `<annotationProcessorPaths>` | `annotationProcessor` | Code generation at compile time |

### Regular dependencies

**Maven**
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>
```

**Gradle**
```groovy
implementation 'org.springframework.boot:spring-boot-starter-web'
```
> Versions are omitted in both because Spring Boot's BOM manages them.

### Lombok (compile-only + annotation processor)

**Maven**
```xml
<dependency>
    <groupId>org.projectlombok</groupId>
    <artifactId>lombok</artifactId>
    <optional>true</optional>
</dependency>
<!-- declared separately in annotationProcessorPaths -->
```

**Gradle**
```groovy
compileOnly 'org.projectlombok:lombok'
annotationProcessor 'org.projectlombok:lombok'
```

### MapStruct (implementation + annotation processor)

**Maven**
```xml
<dependency>
    <groupId>org.mapstruct</groupId>
    <artifactId>mapstruct</artifactId>
    <version>${mapstruct.version}</version>
</dependency>
<!-- declared separately in annotationProcessorPaths -->
<path>
    <groupId>org.mapstruct</groupId>
    <artifactId>mapstruct-processor</artifactId>
    <version>${mapstruct.version}</version>
</path>
```

**Gradle**
```groovy
implementation "org.mapstruct:mapstruct:${mapstructVersion}"
annotationProcessor "org.mapstruct:mapstruct-processor:${mapstructVersion}"
```

---

## Unit Tests vs Integration Tests

Maven uses two separate plugins: `surefire` for unit tests and `failsafe` for integration tests (files ending in `*IT`).

Gradle uses two tasks of the same `Test` type — one excludes `*IT` classes, the other includes only `*IT` classes.

### Unit tests (exclude `*IT`)

**Maven (`maven-surefire-plugin`)**
```xml
<configuration>
    <excludes>
        <exclude>**/*IT.java</exclude>
    </excludes>
    <argLine>-Dnet.bytebuddy.experimental=true</argLine>
    <environmentVariables>
        <TESTCONTAINERS_RYUK_DISABLED>true</TESTCONTAINERS_RYUK_DISABLED>
    </environmentVariables>
</configuration>
```

**Gradle**
```groovy
test {
    useJUnitPlatform()
    exclude '**/*IT.class'
    jvmArgs '-Dnet.bytebuddy.experimental=true'
    environment 'TESTCONTAINERS_RYUK_DISABLED', 'true'
}
```

### Integration tests (only `*IT`)

**Maven (`maven-failsafe-plugin`)**
```xml
<configuration>
    <argLine>-Dnet.bytebuddy.experimental=true</argLine>
    <environmentVariables>
        <TESTCONTAINERS_RYUK_DISABLED>true</TESTCONTAINERS_RYUK_DISABLED>
        <TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE>/tmp/docker-proxy.sock</TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE>
    </environmentVariables>
</configuration>
<executions>
    <execution>
        <goals>
            <goal>integration-test</goal>
            <goal>verify</goal>
        </goals>
    </execution>
</executions>
```

**Gradle**
```groovy
tasks.register('integrationTest', Test) {
    useJUnitPlatform()
    include '**/*IT.class'
    jvmArgs '-Dnet.bytebuddy.experimental=true'
    environment 'TESTCONTAINERS_RYUK_DISABLED', 'true'
    environment 'TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE', '/tmp/docker-proxy.sock'
}

check.dependsOn integrationTest
```
> `check.dependsOn integrationTest` ensures integration tests run as part of the standard `./gradlew build`.

---

## Common Commands

| Goal | Maven | Gradle |
|---|---|---|
| Build & package | `./mvnw package` | `./gradlew build` |
| Run the app | `./mvnw spring-boot:run` | `./gradlew bootRun` |
| Unit tests only | `./mvnw test` | `./gradlew test` |
| Integration tests | `./mvnw verify` | `./gradlew integrationTest` |
| Skip tests | `./mvnw package -DskipTests` | `./gradlew build -x test` |
| Clean | `./mvnw clean` | `./gradlew clean` |
| Clean + build | `./mvnw clean package` | `./gradlew clean build` |

---

## Key Differences Summary

- **Configuration style**: Maven is declarative XML; Gradle is a Groovy (or Kotlin) script — more flexible and less verbose.
- **Performance**: Gradle has incremental builds and a build cache, making repeated builds faster than Maven.
- **Dependency scopes**: Maven has a fixed set of scopes; Gradle configurations are more granular (`compileOnly`, `runtimeOnly`, `annotationProcessor`, etc.).
- **Plugins**: Maven plugins are XML-configured; Gradle plugins are applied with a single line and configured in code.
- **Integration tests**: Maven needs a separate plugin (`failsafe`); Gradle reuses the same `Test` task type with different filters.