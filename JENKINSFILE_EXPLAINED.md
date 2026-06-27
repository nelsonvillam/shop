# Jenkinsfile Explained

This document explains every block and line of the `Jenkinsfile` in detail.

---

## Full File at a Glance

```
pipeline
├── agent
├── triggers
├── environment
├── stages
│   ├── Checkout
│   ├── Lint
│   ├── Tests (parallel, failFast)
│   │   ├── Unit Test
│   │   └── Integration Test
│   ├── SonarQube Analysis
│   ├── Quality Gate
│   ├── Build
│   ├── Docker Build
│   ├── Docker Push
│   └── Deploy
└── post
    ├── always
    ├── success
    └── failure
```

---

## Top Level

```groovy
pipeline {
```

Declares a **declarative pipeline** — Jenkins' structured DSL for defining CI/CD workflows. Everything inside this block defines what the pipeline does, when it runs, and where.

---

## `agent`

```groovy
agent any
```

Tells Jenkins to run the pipeline on **any available agent** (executor). Since this is a local Jenkins instance with a single node, it always runs on that node. This is the default agent for all stages unless a stage overrides it with its own `agent` block.

---

## `triggers`

```groovy
triggers {
    githubPush()
}
```

Defines **when the pipeline is triggered automatically**. `githubPush()` tells Jenkins to start a new build whenever it receives a push event from GitHub via webhook. This requires:
- The GitHub plugin installed in Jenkins
- A webhook configured in the GitHub repository pointing to `https://<jenkins-url>/github-webhook/`
- ngrok running to expose the local Jenkins to GitHub (since Jenkins runs locally)

---

## `environment`

```groovy
environment {
    IMAGE_NAME        = 'nelsonvillam/shop'
    IMAGE_TAG         = "${env.BUILD_NUMBER}"
    GRADLE_USER_HOME  = "${env.WORKSPACE}/.gradle"
    SONAR_USER_HOME   = "${env.WORKSPACE}/.sonar"
}
```

Defines **environment variables available to all stages** in the pipeline.

| Variable | Value | Purpose |
|---|---|---|
| `IMAGE_NAME` | `nelsonvillam/shop` | Docker Hub repository name used when building and pushing the image |
| `IMAGE_TAG` | `${env.BUILD_NUMBER}` | Jenkins auto-incremented build number used as the image tag (e.g. `shop:12`) |
| `GRADLE_USER_HOME` | `${WORKSPACE}/.gradle` | Redirects Gradle's cache directory to the workspace, avoiding permission errors when running inside Docker containers where the default home (`/`) is read-only |
| `SONAR_USER_HOME` | `${WORKSPACE}/.sonar` | Redirects SonarQube scanner's cache to the workspace for the same reason |

---

## `stages`

```groovy
stages {
```

Container block for all pipeline stages. Stages run **sequentially by default** — each one starts only after the previous one completes successfully (unless `parallel` is used).

---

### Stage: `Checkout`

```groovy
stage('Checkout') {
    steps {
        checkout scm
    }
}
```

**When it runs:** first, at the start of every build.

`checkout scm` clones or fetches the repository configured in the Jenkins job (GitHub in this case) and checks out the commit that triggered the build. `scm` refers to the Source Control Management configuration defined in the Jenkins job settings.

> Jenkins actually performs an implicit checkout before this stage too (the `Declarative: Checkout SCM` step visible in logs). This explicit `Checkout` stage makes it visible in the pipeline UI and allows it to be tracked separately.

---

### Stage: `Lint`

```groovy
stage('Lint') {
    agent {
        docker {
            image 'eclipse-temurin:21-jdk'
            reuseNode true
            args "-e HOME=${env.WORKSPACE}"
        }
    }
    steps {
        sh './gradlew checkstyleMain pmdMain spotbugsMain --no-daemon'
    }
}
```

**When it runs:** after Checkout, before Tests.

Runs three static analysis tools against the main source set inside an `eclipse-temurin:21-jdk` container:

| Task | Tool | What it checks |
|---|---|---|
| `checkstyleMain` | Checkstyle | Code style: naming conventions, no star imports, no unused imports |
| `pmdMain` | PMD | Best practices and error-prone patterns in source code |
| `spotbugsMain` | SpotBugs | Bug patterns in compiled bytecode |

A fourth tool, **ErrorProne**, runs automatically during compilation inside this stage (and every other stage that compiles Java) — it requires no separate task.

The build fails immediately if any violation is found. HTML reports for all three tools are published in `post { always { } }` so they appear even on failed builds.

---

### Stage: `Tests`

```groovy
stage('Tests') {
    failFast true
    parallel {
```

**When it runs:** after Lint completes.

- `failFast true` — if either parallel branch fails, the other is **cancelled immediately** rather than waiting for it to finish. This saves time on broken builds.
- `parallel` — runs Unit Test and Integration Test **simultaneously**. The pipeline waits for both to finish (or one to fail) before moving to SonarQube Analysis.

---

#### Parallel Branch: `Unit Test`

```groovy
stage('Unit Test') {
    agent {
        docker {
            image 'eclipse-temurin:21-jdk'
            reuseNode true
            args "-e HOME=${env.WORKSPACE}"
        }
    }
    steps {
        sh './gradlew test --no-daemon'
    }
    post {
        always {
            junit '**/build/test-results/test/**/*.xml'
        }
    }
}
```

**When it runs:** in parallel with Integration Test, after Checkout.

- `agent { docker { ... } }` — overrides the pipeline-level agent for this stage only. Jenkins spins up a temporary `eclipse-temurin:21-jdk` Docker container to run this stage, ensuring a clean and consistent Java 21 environment.
- `reuseNode true` — mounts the same workspace directory into the container so the Gradle build files and output are accessible without copying.
- `args "-e HOME=${env.WORKSPACE}"` — sets the `HOME` environment variable inside the container to the workspace path. This prevents `tree-sitter` and `jgit` from trying to write to `/` (the container's default home), which is read-only.
- `sh './gradlew test --no-daemon'` — runs all test classes **excluding** those ending in `IT` (integration tests). `--no-daemon` prevents Gradle from starting a background daemon inside the container, which would be wasted since the container is discarded after the stage.
- `junit '**/build/test-results/test/**/*.xml'` — runs **always** (even if tests fail) and publishes the JUnit XML results to Jenkins so test trends and failures are visible in the build UI.

---

#### Parallel Branch: `Integration Test`

```groovy
stage('Integration Test') {
    steps {
        sh './gradlew integrationTest --no-daemon'
    }
    post {
        always {
            junit '**/build/test-results/integrationTest/**/*.xml'
        }
    }
}
```

**When it runs:** in parallel with Unit Test, after Checkout.

- No `agent` block — runs directly on the Jenkins host (not inside a Docker container). This is intentional: integration tests use **Testcontainers**, which spins up a real MongoDB container. Testcontainers needs access to the Docker socket, which is simpler to access from the host than from inside a container.
- `sh './gradlew integrationTest --no-daemon'` — runs only test classes ending in `IT`.
- `junit` — same as unit tests, publishes results to Jenkins always.

---

### Stage: `SonarQube Analysis`

```groovy
stage('SonarQube Analysis') {
    agent {
        docker {
            image 'eclipse-temurin:21-jdk'
            reuseNode true
            args "-e HOME=${env.WORKSPACE}"
        }
    }
    steps {
        withSonarQubeEnv('sonarqube') {
            sh './gradlew jacocoTestReport sonar --no-daemon'
        }
    }
}
```

**When it runs:** after both Unit Test and Integration Test complete successfully.

- Runs inside an `eclipse-temurin:21-jdk` container (same reason as Unit Test — needs Java 21).
- `withSonarQubeEnv('sonarqube')` — injects the SonarCloud server URL and authentication token as environment variables. The name `'sonarqube'` must match the server name configured in **Manage Jenkins → Configure System → SonarQube servers**.
- `jacocoTestReport` — merges the `.exec` coverage data files produced by both `test` and `integrationTest` runs into a single XML + HTML report.
- `sonar` — sends the merged coverage report and source analysis to SonarCloud.

---

### Stage: `Quality Gate`

```groovy
stage('Quality Gate') {
    steps {
        timeout(time: 5, unit: 'MINUTES') {
            waitForQualityGate abortPipeline: true
        }
    }
}
```

**When it runs:** after SonarQube Analysis completes.

- `waitForQualityGate` — pauses the pipeline and polls SonarCloud until the analysis task finishes and a quality gate result is available.
- `abortPipeline: true` — if the quality gate status is `FAILED` (e.g. coverage dropped below the threshold, new bugs introduced), the pipeline is **aborted** and all subsequent stages are skipped.
- `timeout(time: 5, unit: 'MINUTES')` — if SonarCloud doesn't respond within 5 minutes, the stage fails rather than hanging indefinitely.

---

### Stage: `Build`

```groovy
stage('Build') {
    agent {
        docker {
            image 'eclipse-temurin:21-jdk'
            reuseNode true
            args "-e HOME=${env.WORKSPACE}"
        }
    }
    steps {
        sh './gradlew bootJar --no-daemon'
    }
}
```

**When it runs:** after Quality Gate passes.

- Runs inside `eclipse-temurin:21-jdk` to compile and package the application.
- `bootJar` — builds a Spring Boot **fat jar** containing the application and all its dependencies at `build/libs/shop-0.0.1-SNAPSHOT.jar`. This jar is what gets copied into the Docker image in the next stage.

---

### Stage: `Docker Build`

```groovy
stage('Docker Build') {
    steps {
        sh "docker build -t ${IMAGE_NAME}:${IMAGE_TAG} ."
        sh "docker tag ${IMAGE_NAME}:${IMAGE_TAG} ${IMAGE_NAME}:latest"
    }
}
```

**When it runs:** after Build completes.

- Runs on the Jenkins host (no Docker agent) so it has direct access to the Docker daemon.
- `docker build -t nelsonvillam/shop:${BUILD_NUMBER} .` — builds the Docker image using the `Dockerfile` in the project root, tagging it with the build number (e.g. `nelsonvillam/shop:15`).
- `docker tag ... :latest` — also tags the same image as `latest` so it can be pulled without specifying a version.

---

### Stage: `Docker Push`

```groovy
stage('Docker Push') {
    steps {
        sh "docker push ${IMAGE_NAME}:${IMAGE_TAG}"
        sh "docker push ${IMAGE_NAME}:latest"
    }
}
```

**When it runs:** after Docker Build completes.

Pushes both tags (`BUILD_NUMBER` and `latest`) to Docker Hub. Authentication uses the credentials stored in `~/.docker/config.json` from a prior manual `docker login`. No `docker login` step is included in the pipeline because re-running it on every build would fail due to macOS Keychain restrictions in the Jenkins context.

---

### Stage: `Deploy`

```groovy
stage('Deploy') {
    steps {
        withCredentials([
            string(credentialsId: 'shop/mongo-user',     variable: 'MONGO_USER'),
            string(credentialsId: 'shop/mongo-password', variable: 'MONGO_PASSWORD')
        ]) {
            sh """
                docker stop shop || true
                docker rm shop || true
                docker compose down --remove-orphans || true
                docker compose up -d
            """
        }
    }
}
```

**When it runs:** after Docker Push completes. Skipped if any earlier stage failed.

Credentials are fetched at deploy time from **AWS Secrets Manager** via the **AWS Secrets Manager Credentials Provider** Jenkins plugin. They are never stored in Jenkins and are automatically masked in build logs.

- `withCredentials` — fetches the secrets from AWS Secrets Manager using the credential IDs `shop/mongo-user` and `shop/mongo-password`, which map directly to the secret names in Secrets Manager. The values are exposed as environment variables (`MONGO_USER`, `MONGO_PASSWORD`) only within this block and are masked as `****` in the Jenkins console output.
- `docker stop shop || true` — stops the running container named `shop` if it exists. `|| true` prevents the step from failing if the container isn't running.
- `docker rm shop || true` — removes the stopped container.
- `docker compose down --remove-orphans || true` — tears down any remaining compose services and removes containers not defined in the current `docker-compose.yml`.
- `docker compose up -d` — starts the application in detached mode using the latest image. Docker Compose reads `MONGO_USER` and `MONGO_PASSWORD` from the environment automatically.

**Prerequisites:**
- **AWS Secrets Manager Credentials Provider** plugin installed in Jenkins (**Manage Jenkins → Plugins → Available plugins**)
- AWS region configured in **Manage Jenkins → System → AWS Secrets Manager** (e.g. `sa-east-1`)
- The Jenkins host has an IAM role or IAM user with `secretsmanager:GetSecretValue` permission on `arn:aws:secretsmanager:*:*:secret:shop/*`
- Secrets created in AWS Secrets Manager:
  - `shop/mongo-user` — MongoDB username
  - `shop/mongo-password` — MongoDB password

---

## `post`

```groovy
post {
```

Defines actions that run **after all stages complete**, regardless of the pipeline result. There are three blocks used here.

---

### `always`

```groovy
always {
    publishHTML(target: [reportName: 'Checkstyle Report', reportDir: 'build/reports/checkstyle', reportFiles: 'main.html', ...])
    publishHTML(target: [reportName: 'PMD Report',        reportDir: 'build/reports/pmd',        reportFiles: 'main.html', ...])
    publishHTML(target: [reportName: 'SpotBugs Report',   reportDir: 'build/reports/spotbugs',   reportFiles: 'main.html', ...])
    publishHTML(target: [reportName: 'Unit Test Report',        reportDir: 'build/reports/tests/test',            reportFiles: 'index.html', ...])
    publishHTML(target: [reportName: 'Integration Test Report', reportDir: 'build/reports/tests/integrationTest', reportFiles: 'index.html', ...])
    publishHTML(target: [reportName: 'Coverage Report',         reportDir: 'build/reports/jacoco/test/html',      reportFiles: 'index.html', ...])
}
```

**When it runs:** after every build, whether it passed or failed.

Publishes six HTML reports to the Jenkins build page using the HTML Publisher plugin:

| Report | Source |
|---|---|
| Checkstyle Report | Checkstyle HTML report for style violations |
| PMD Report | PMD HTML report for best-practice violations |
| SpotBugs Report | SpotBugs HTML report for bug patterns |
| Unit Test Report | Gradle's HTML test report for the `test` task |
| Integration Test Report | Gradle's HTML test report for the `integrationTest` task |
| Coverage Report | JaCoCo HTML coverage report combining both test runs |

- `keepAll: true` — keeps reports from all previous builds, not just the latest.
- `allowMissing: true` — doesn't fail the post step if the report file wasn't generated (e.g. if the build failed before tests ran).
- `alwaysLinkToLastBuild: true` — the report link on the job page always points to the most recent build's report.

---

### `success`

```groovy
success {
    echo "Deployment of ${IMAGE_NAME}:${IMAGE_TAG} succeeded."
}
```

**When it runs:** only when all stages completed successfully.

Prints a confirmation message with the image name and build number to the build log.

---

### `failure`

```groovy
failure {
    echo "Pipeline failed. Check the logs above."
}
```

**When it runs:** when any stage failed and the pipeline was aborted.

Prints a failure message to the build log. This could be extended to send a Slack or email notification.
