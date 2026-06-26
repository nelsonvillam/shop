# Testing & Pipeline Configuration

## Overview

The project uses Gradle with JaCoCo for test coverage and Jenkins for CI/CD. Tests are split into two separate tasks: unit tests and integration tests.

---

## Test Configuration (`build.gradle`)

### Unit Tests

```groovy
test {
    useJUnitPlatform()
    exclude '**/*IT.class'
    jvmArgs '-Dnet.bytebuddy.experimental=true'
    environment 'TESTCONTAINERS_RYUK_DISABLED', 'true'
}
```

- Runs all test classes **except** those ending in `IT`
- Naming convention: `*Test.java`
- JUnit 5 platform
- Testcontainers Ryuk disabled (required for local Docker environments)

### Integration Tests

```groovy
tasks.register('integrationTest', Test) {
    useJUnitPlatform()
    include '**/*IT.class'
    jvmArgs '-Dnet.bytebuddy.experimental=true'
    environment 'TESTCONTAINERS_RYUK_DISABLED', 'true'
    testLogging {
        events 'passed', 'failed', 'skipped'
    }
}
```

- Runs only classes ending in `IT` (e.g. `ProductServiceIT.java`)
- Uses Testcontainers to spin up a real MongoDB instance
- Also checks for a Testcontainers proxy socket at `/tmp/docker-tc-proxy.sock` when running inside Docker

### Running Tests Locally

```bash
# Unit tests only
./gradlew test

# Integration tests only
./gradlew integrationTest

# Both
./gradlew test integrationTest
```

---

## Test Coverage (`build.gradle`)

### JaCoCo Configuration

```groovy
jacoco {
    toolVersion = '0.8.12'
}

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

- Coverage is collected from both unit and integration test runs (merged)
- **XML report** is used by SonarCloud for quality gate analysis
- **HTML report** is published to Jenkins for visual inspection

### Excluded from Coverage

| Package | Reason |
|---|---|
| `dto` | Data transfer objects — no business logic |
| `model` | Domain entities — no business logic |
| `entity` | Persistence entities — no business logic |
| `mapper` | MapStruct generated code |
| `config` | Spring configuration classes |

Coverage is measured only on `controller`, `service`, and `repository` packages.

### Generating the Coverage Report Locally

```bash
./gradlew test jacocoTestReport
```

Report output: `build/reports/jacoco/test/html/index.html`

---

## Jenkins Pipeline (`Jenkinsfile`)

### Trigger

```groovy
triggers {
    pollSCM('* * * * *')
}
```

Polls GitHub every minute and triggers the pipeline automatically when new commits are detected. Used instead of webhooks because Jenkins runs locally and is not reachable from GitHub.

### Environment Variables

| Variable | Value | Purpose |
|---|---|---|
| `IMAGE_NAME` | `nelsonvillam/shop` | Docker Hub image name |
| `IMAGE_TAG` | `${BUILD_NUMBER}` | Unique tag per build |
| `GRADLE_USER_HOME` | `${WORKSPACE}/.gradle` | Avoids Gradle cache permission errors in Docker |
| `SONAR_USER_HOME` | `${WORKSPACE}/.sonar` | Avoids SonarQube cache permission errors in Docker |

> Stages that run inside a Docker container also pass `-e HOME=${WORKSPACE}` via `args` to fix read-only home directory errors (tree-sitter, jgit).

### Pipeline Stages

```
Checkout → Unit Test → Integration Test → SonarQube Analysis → Quality Gate → Build → Docker Build → Docker Push → Deploy
```

| Stage | Agent | Command | Notes |
|---|---|---|---|
| Checkout | Jenkins host | `checkout scm` | Fetches source from GitHub |
| Unit Test | `eclipse-temurin:21-jdk` | `./gradlew test jacocoTestReport` | Runs unit tests and generates coverage |
| Integration Test | Jenkins host | `./gradlew integrationTest` | Runs IT tests via Testcontainers |
| SonarQube Analysis | `eclipse-temurin:21-jdk` | `./gradlew jacocoTestReport sonar` | Sends coverage + analysis to SonarCloud |
| Quality Gate | Jenkins host | `waitForQualityGate` | Aborts pipeline if SonarCloud gate fails |
| Build | `eclipse-temurin:21-jdk` | `./gradlew bootJar` | Builds the Spring Boot fat jar |
| Docker Build | Jenkins host | `docker build` | Builds and tags the Docker image |
| Docker Push | Jenkins host | `docker push` | Pushes image to Docker Hub |
| Deploy | Jenkins host | `docker compose up -d` | Redeploys the app locally |

### HTML Reports in Jenkins

After each build, three report links appear on the Jenkins build page:

| Link | Source Directory |
|---|---|
| Unit Test Report | `build/reports/tests/test/index.html` |
| Integration Test Report | `build/reports/tests/integrationTest/index.html` |
| Coverage Report | `build/reports/jacoco/test/html/index.html` |

> If reports appear unstyled, run the following in **Manage Jenkins → Script Console**:
> ```groovy
> System.setProperty("hudson.model.DirectoryBrowserSupport.CSP", "")
> ```

### SonarCloud Integration

SonarCloud is configured in `build.gradle`:

```groovy
sonar {
    properties {
        property 'sonar.projectKey',   'nelsonvillam_shop'
        property 'sonar.organization', 'nelsonvillam'
        property 'sonar.coverage.jacoco.xmlReportPaths', 'build/reports/jacoco/test/jacocoTestReport.xml'
    }
}
```

The Jenkins server must have a SonarQube server configured under the name `sonarqube` in **Manage Jenkins → Configure System**.

### Docker Credentials

Docker Hub credentials are stored as plain base64 in `~/.docker/config.json` (no credential helper). A one-time manual `docker login` is required after any credential change:

```bash
docker login -u nelsonvillam
```

Use a Docker Hub Personal Access Token (PAT) with **Read & Write** permissions as the password.
