pipeline {
    agent any

    triggers {
        githubPush()
    }

    environment {
        IMAGE_NAME         = 'nelsonvillam/shop'
        GATEWAY_IMAGE_NAME = 'nelsonvillam/gateway'
        IMAGE_TAG          = "${env.BUILD_NUMBER}"
        GRADLE_USER_HOME   = "${env.WORKSPACE}/.gradle"
        SONAR_USER_HOME    = "${env.WORKSPACE}/.sonar"
        AWS_REGION         = 'sa-east-1'
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Lint') {
            agent {
                docker {
                    image 'eclipse-temurin:21-jdk'
                    reuseNode true
                    args "-e HOME=${env.WORKSPACE}"
                }
            }
            steps {
                sh 'mkdir -p build/reports/problems'
                sh './gradlew checkstyleMain pmdMain spotbugsMain --no-daemon'
            }
        }

        stage('Compile Tests') {
            agent {
                docker {
                    image 'eclipse-temurin:21-jdk'
                    reuseNode true
                    args "-e HOME=${env.WORKSPACE}"
                }
            }
            steps {
                sh 'mkdir -p build/reports/problems'
                sh './gradlew compileTestJava --no-daemon'
            }
        }

        stage('Tests') {
            failFast true
            parallel {
                stage('Unit Test') {
                    agent {
                        docker {
                            image 'eclipse-temurin:21-jdk'
                            reuseNode true
                            args "-e HOME=${env.WORKSPACE}"
                        }
                    }
                    steps {
                        sh 'mkdir -p build/reports/problems'
                        sh './gradlew test --no-daemon'
                    }
                    post {
                        always {
                            junit '**/build/test-results/test/**/*.xml'
                        }
                    }
                }
                stage('Integration Test') {
                    steps {
                        sh 'mkdir -p build/reports/problems'
                        sh './gradlew integrationTest --no-daemon'
                    }
                    post {
                        always {
                            junit '**/build/test-results/integrationTest/**/*.xml'
                        }
                    }
                }
            }
        }

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
                    sh 'mkdir -p build/reports/problems'
                    sh './gradlew jacocoTestReport sonar --no-daemon'
                }
            }
        }

        stage('Quality Gate') {
            steps {
                timeout(time: 5, unit: 'MINUTES') {
                    waitForQualityGate abortPipeline: true
                }
            }
        }

        stage('Build') {
            agent {
                docker {
                    image 'eclipse-temurin:21-jdk'
                    reuseNode true
                    args "-e HOME=${env.WORKSPACE}"
                }
            }
            steps {
                sh 'mkdir -p build/reports/problems'
                sh './gradlew bootJar --no-daemon'
            }
        }

        stage('Docker Build & Push') {
            steps {
                sh "docker buildx create --use --name multibuilder 2>/dev/null || true"
                // Build shop and gateway images in parallel
                parallel(
                    shop: {
                        sh """
                            docker buildx build \
                                --platform linux/amd64,linux/arm64 \
                                -t ${IMAGE_NAME}:${IMAGE_TAG} \
                                -t ${IMAGE_NAME}:latest \
                                --push \
                                .
                        """
                    },
                    gateway: {
                        sh """
                            cd gateway && \
                            ./gradlew bootJar --no-daemon && \
                            docker buildx build \
                                --platform linux/amd64,linux/arm64 \
                                -t ${GATEWAY_IMAGE_NAME}:${IMAGE_TAG} \
                                -t ${GATEWAY_IMAGE_NAME}:latest \
                                --push \
                                .
                        """
                    }
                )
            }
        }

        stage('Deploy to Kubernetes') {
            // Deploys to the local Docker Desktop Kubernetes cluster.
            // Secrets are managed by External Secrets Operator — fetched automatically
            // from AWS Secrets Manager via the aws-credentials k8s Secret.
            steps {
                sh """
                    # ── 1. Switch to local cluster ───────────────────────────────────
                    kubectl config use-context docker-desktop

                    # ── 2. Apply manifests (includes ExternalSecret resources) ────────
                    kubectl apply -k k8s/overlays/local/

                    # ── 3. Upsert aws-credentials secret for ESO (no xtrace) ─────────
                    set +x
                    kubectl create secret generic aws-credentials \
                        --namespace shop \
                        --from-literal=access-key-id="\${AWS_ACCESS_KEY_ID}" \
                        --from-literal=secret-access-key="\${AWS_SECRET_ACCESS_KEY}" \
                        --from-literal=session-token="\${AWS_SESSION_TOKEN:-}" \
                        --save-config --dry-run=client -o yaml | kubectl apply -f -
                    set -x

                    # ── 4. Wait for ESO to sync all secrets from AWS ──────────────────
                    for es in mongodb-credentials mongodb-keyfile shop-secret; do
                        kubectl wait externalsecret/\$es \
                            --namespace shop \
                            --for=condition=Ready \
                            --timeout=60s
                    done

                    # ── 5. Deploy with pinned image tags ─────────────────────────────
                    sed 's|${IMAGE_NAME}:latest|${IMAGE_NAME}:${IMAGE_TAG}|g' \
                        k8s/base/shop/deployment.yaml | kubectl apply -f -

                    sed 's|${GATEWAY_IMAGE_NAME}:latest|${GATEWAY_IMAGE_NAME}:${IMAGE_TAG}|g' \
                        k8s/base/gateway/deployment.yaml | kubectl apply -f -

                    kubectl apply -f k8s/base/shop/configmap.yaml
                    kubectl apply -f k8s/base/shop/service.yaml
                    kubectl apply -f k8s/base/shop/ingress.yaml
                    kubectl apply -f k8s/base/gateway/service.yaml
                """

                sh """
                    kubectl rollout status deployment/shop --namespace shop --timeout=5m
                    kubectl rollout status deployment/gateway --namespace shop --timeout=5m
                """
            }

            post {
                failure {
                    sh """
                        kubectl rollout undo deployment/shop --namespace shop || true
                        kubectl rollout undo deployment/gateway --namespace shop || true
                    """
                }
            }
        }
    }

    post {
        always {
            publishHTML(target: [
                reportName : 'Checkstyle Report',
                reportDir  : 'build/reports/checkstyle',
                reportFiles: 'main.html',
                keepAll    : true,
                allowMissing: true,
                alwaysLinkToLastBuild: true
            ])
            publishHTML(target: [
                reportName : 'PMD Report',
                reportDir  : 'build/reports/pmd',
                reportFiles: 'main.html',
                keepAll    : true,
                allowMissing: true,
                alwaysLinkToLastBuild: true
            ])
            publishHTML(target: [
                reportName : 'SpotBugs Report',
                reportDir  : 'build/reports/spotbugs',
                reportFiles: 'main.html',
                keepAll    : true,
                allowMissing: true,
                alwaysLinkToLastBuild: true
            ])
            publishHTML(target: [
                reportName : 'Unit Test Report',
                reportDir  : 'build/reports/tests/test',
                reportFiles: 'index.html',
                keepAll    : true,
                allowMissing: true,
                alwaysLinkToLastBuild: true
            ])
            publishHTML(target: [
                reportName : 'Integration Test Report',
                reportDir  : 'build/reports/tests/integrationTest',
                reportFiles: 'index.html',
                keepAll    : true,
                allowMissing: true,
                alwaysLinkToLastBuild: true
            ])
            publishHTML(target: [
                reportName : 'Coverage Report',
                reportDir  : 'build/reports/jacoco/test/html',
                reportFiles: 'index.html',
                keepAll    : true,
                allowMissing: true,
                alwaysLinkToLastBuild: true
            ])
        }
        success {
            echo "Deployed ${IMAGE_NAME}:${IMAGE_TAG} to Kubernetes successfully."
        }
        failure {
            echo "Pipeline failed. Check the logs above."
        }
    }
}
