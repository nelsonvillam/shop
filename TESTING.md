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
    githubPush()
}
```

Triggers the pipeline instantly when GitHub sends a push event via webhook. Requires ngrok running to expose the local Jenkins instance to GitHub.

---

### Triggering via GitHub Webhooks (when Jenkins is publicly accessible)

If Jenkins is hosted on a server reachable from the internet (e.g. a VPS, cloud VM, or exposed via ngrok), webhooks can replace `pollSCM` for instant pipeline triggering on every push — no polling delay.

#### Prerequisites

- **GitHub plugin** installed in Jenkins (`Manage Jenkins → Plugins → Installed` — search for "GitHub")
- Jenkins URL must be publicly accessible (e.g. `https://jenkins.example.com`)

#### Step 1 — Update the Jenkinsfile trigger

Replace `pollSCM` with `githubPush()`:

```groovy
triggers {
    githubPush()
}
```

#### Step 2 — Configure the GitHub server in Jenkins

1. Go to **Manage Jenkins → Configure System**
2. Scroll to **GitHub** section → click **Add GitHub Server**
3. Set the API URL to `https://api.github.com`
4. Add a GitHub Personal Access Token as credentials (needs `repo` and `admin:repo_hook` scopes)
5. Click **Test connection** to verify

#### Step 3 — Add the webhook in GitHub

1. Go to your GitHub repository → **Settings → Webhooks → Add webhook**
2. Set **Payload URL** to: `https://<your-jenkins-url>/github-webhook/`
3. Set **Content type** to `application/json`
4. Select **Just the push event**
5. Check **Active** and click **Add webhook**

GitHub will send a ping request immediately — a green checkmark confirms Jenkins received it.

#### Step 4 — Run the pipeline once manually

After updating the Jenkinsfile, run the pipeline once manually in Jenkins so it registers the new `githubPush()` trigger. From that point on, every push to GitHub will trigger the pipeline instantly.

#### Using ngrok for local Jenkins

If Jenkins is still running locally but you want to test webhooks, ngrok can temporarily expose it:

```bash
ngrok http 8080
```

Use the generated URL (e.g. `https://abc123.ngrok.io`) as the webhook Payload URL:

```
https://abc123.ngrok.io/github-webhook/
```

> The ngrok URL changes on every restart unless you have a paid static domain. For a stable local setup, `pollSCM` is more practical.

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
                       ┌─ Unit Test ────────┐
Checkout → Tests ──────┤                    ├──→ SonarQube Analysis → Quality Gate → Build → Docker Build → Docker Push → Deploy
                       └─ Integration Test ─┘
```

Unit Test and Integration Test run in parallel inside the `Tests` stage. SonarQube Analysis waits for both to complete before running.

| Stage | Agent | Command | Notes |
|---|---|---|---|
| Checkout | Jenkins host | `checkout scm` | Fetches source from GitHub |
| Tests / Unit Test | `eclipse-temurin:21-jdk` | `./gradlew test` | Runs unit tests in parallel with integration tests |
| Tests / Integration Test | Jenkins host | `./gradlew integrationTest` | Runs IT tests via Testcontainers in parallel with unit tests |
| SonarQube Analysis | `eclipse-temurin:21-jdk` | `./gradlew jacocoTestReport sonar` | Merges coverage from both test runs, sends to SonarCloud |
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
