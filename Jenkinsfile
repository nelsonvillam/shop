pipeline {
    agent any

    triggers {
        githubPush()
    }

    environment {
        IMAGE_NAME       = 'nelsonvillam/shop'
        IMAGE_TAG        = "${env.BUILD_NUMBER}"
        GRADLE_USER_HOME = "${env.WORKSPACE}/.gradle"
        SONAR_USER_HOME  = "${env.WORKSPACE}/.sonar"
        AWS_REGION       = 'sa-east-1'
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
                sh """
                    docker buildx build \
                        --platform linux/amd64,linux/arm64 \
                        -t ${IMAGE_NAME}:${IMAGE_TAG} \
                        -t ${IMAGE_NAME}:latest \
                        --push \
                        .
                """
            }
        }

        stage('Deploy to Kubernetes') {
            steps {
                withCredentials([
                    string(credentialsId: 'aws-access-key-id',     variable: 'CI_AWS_ACCESS_KEY_ID'),
                    string(credentialsId: 'aws-secret-access-key', variable: 'CI_AWS_SECRET_ACCESS_KEY')
                ]) {
                    sh """
                        kubectl config use-context docker-desktop

                        # ── Refresh aws-credentials so ESO can reach Secrets Manager ──
                        set +x
                        kubectl create secret generic aws-credentials \
                            --namespace shop \
                            --from-literal=access-key-id="\${CI_AWS_ACCESS_KEY_ID}" \
                            --from-literal=secret-access-key="\${CI_AWS_SECRET_ACCESS_KEY}" \
                            --from-literal=session-token="" \
                            --dry-run=client -o yaml | kubectl replace --force -f -
                        set -x

                        kubectl annotate secretstore aws-secretsmanager \
                            --namespace shop \
                            force-sync=\$(date +%s) \
                            --overwrite

                        if ! kubectl wait secretstore/aws-secretsmanager \
                                --namespace shop \
                                --for=condition=Ready \
                                --timeout=60s; then
                            echo "=== SecretStore failed — check aws-credentials values ==="
                            kubectl describe secretstore aws-secretsmanager -n shop || true
                            exit 1
                        fi

                        for es in mongodb-credentials mongodb-keyfile shop-secret; do
                            kubectl annotate externalsecret/\$es \
                                --namespace shop \
                                force-sync=\$(date +%s) \
                                --overwrite
                        done

                        for es in mongodb-credentials mongodb-keyfile shop-secret; do
                            if ! kubectl wait externalsecret/\$es \
                                    --namespace shop \
                                    --for=condition=Ready \
                                    --timeout=120s; then
                                echo "=== ExternalSecret \$es failed ==="
                                kubectl describe externalsecret/\$es -n shop || true
                                exit 1
                            fi
                        done

                        kubectl apply -f k8s/configmap.yaml
                        kubectl apply -f k8s/service.yaml

                        sed 's|${IMAGE_NAME}:latest|${IMAGE_NAME}:${IMAGE_TAG}|g' \
                            k8s/deployment.yaml | kubectl apply -f -
                    """
                }
                sh "kubectl rollout status deployment/shop --namespace shop --timeout=5m"
            }
            post {
                failure {
                    sh "kubectl rollout undo deployment/shop --namespace shop || true"
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
