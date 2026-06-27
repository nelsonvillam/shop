# check.sh Explained

`check.sh` is a local pipeline script that runs the same quality checks as Jenkins, in the same order, before you push. It lets you catch lint violations, broken tests, and coverage gaps on your own machine instead of waiting for the pipeline to fail.

---

## Usage

```bash
# Full check — lint + unit tests + integration tests + coverage + build
./check.sh

# Skip integration tests (faster, no Docker required)
./check.sh --skip-it

# Skip the final JAR build (just validate quality)
./check.sh --skip-build

# Both flags combined
./check.sh --skip-it --skip-build
```

---

## What It Does, Step by Step

### 1. Lint

```bash
./gradlew checkstyleMain pmdMain spotbugsMain --no-daemon
```

Runs three static analysis tools against the main source set:

| Tool | What it checks |
|---|---|
| Checkstyle | Code style: naming conventions, no star imports, no unused imports |
| PMD | Best practices and error-prone patterns |
| SpotBugs | Bug patterns in compiled bytecode |

If any violation is found the script stops immediately — the same behaviour as the Jenkins `Lint` stage.

---

### 2. Unit Tests

```bash
./gradlew test --no-daemon
```

Runs all test classes that do **not** end in `IT`. Uses JUnit 5. If any test fails the script stops.

---

### 3. Integration Tests

```bash
./gradlew integrationTest --no-daemon
```

Runs only classes ending in `IT`. Requires Docker to be running because Testcontainers spins up a real MongoDB container for each test.

Skip this step with `--skip-it` when you want faster feedback and do not have Docker available.

---

### 4. Coverage Report

```bash
./gradlew jacocoTestReport --no-daemon
```

Merges the coverage data from both the unit test and integration test runs into a single HTML + XML report. The XML is what SonarCloud reads; the HTML is for local inspection.

---

### 5. Build

```bash
./gradlew bootJar --no-daemon
```

Compiles the final Spring Boot fat JAR at `build/libs/shop-0.0.1-SNAPSHOT.jar`. This is the artifact that gets packaged into the Docker image in the pipeline.

Skip this step with `--skip-build` when you only want to check quality without producing an artifact.

---

## Fail Fast

The script uses `set -euo pipefail` and stops on the first failure:

```bash
set -euo pipefail
```

| Flag | Meaning |
|---|---|
| `-e` | Exit immediately if any command returns a non-zero exit code |
| `-u` | Treat unset variables as errors |
| `-o pipefail` | If any command in a pipe fails, the whole pipe fails |

This mirrors the `failFast true` behaviour in the Jenkins pipeline — there is no point continuing to the next step if the current one is already broken.

---

## Report Locations

After a successful run, all reports are available locally:

| Report | Path |
|---|---|
| Checkstyle | `build/reports/checkstyle/main.html` |
| PMD | `build/reports/pmd/main.html` |
| SpotBugs | `build/reports/spotbugs/main.html` |
| Unit tests | `build/reports/tests/test/index.html` |
| Integration tests | `build/reports/tests/integrationTest/index.html` |
| Coverage | `build/reports/jacoco/test/html/index.html` |
| JAR | `build/libs/shop-0.0.1-SNAPSHOT.jar` |

---

## What It Does Not Do

| Step | Why it is not in the script |
|---|---|
| SonarCloud analysis | Requires a SonarCloud token and internet access — not practical to run on every local check |
| Quality Gate | Depends on SonarCloud, so skipped for the same reason |
| Docker build / push | Produces and publishes an image — not needed during development |
| Deploy | Restarts the full stack — not needed just to validate code quality |

These steps only run in Jenkins after the code is pushed.
