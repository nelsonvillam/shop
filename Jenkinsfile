pipeline {
    agent any

    triggers {
        githubPush()
    }

    environment {
        IMAGE_NAME        = 'nelsonvillam/shop'
        IMAGE_TAG         = "${env.BUILD_NUMBER}"
        GRADLE_USER_HOME  = "${env.WORKSPACE}/.gradle"
        SONAR_USER_HOME   = "${env.WORKSPACE}/.sonar"
        EKS_CLUSTER       = 'shop-cluster'
        AWS_REGION        = 'sa-east-1'
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
            // No Jenkins credential needed for cluster access — kubectl authenticates
            // via the EC2 instance IAM role (requires eks:DescribeCluster permission).
            //
            // All secrets are fetched from AWS Secrets Manager on every build so the
            // cluster always reflects the current secret values without manual rotation.
            steps {
                sh """
                    # ── 1. Cluster access ────────────────────────────────────────────
                    aws eks update-kubeconfig \
                        --name ${EKS_CLUSTER} \
                        --region ${AWS_REGION}

                    # ── 2. Fetch secrets from AWS Secrets Manager ─────────────────────
                    MONGO_USER=\$(aws secretsmanager get-secret-value \
                        --secret-id shop/mongo-user \
                        --query SecretString --output text --region ${AWS_REGION})

                    MONGO_PASSWORD=\$(aws secretsmanager get-secret-value \
                        --secret-id shop/mongo-password \
                        --query SecretString --output text --region ${AWS_REGION})

                    ADMIN_PASSWORD=\$(aws secretsmanager get-secret-value \
                        --secret-id shop/admin-password \
                        --query SecretString --output text --region ${AWS_REGION})

                    JWT_SECRET=\$(aws secretsmanager get-secret-value \
                        --secret-id shop/jwt-secret \
                        --query SecretString --output text --region ${AWS_REGION})

                    KEYFILE=\$(aws secretsmanager get-secret-value \
                        --secret-id shop/mongodb-keyfile \
                        --query SecretString --output text --region ${AWS_REGION})

                    # ── 3. Upsert K8s secrets (create if new, update if changed) ─────
                    kubectl create secret generic mongodb-credentials \
                        --namespace shop \
                        --from-literal=username="\${MONGO_USER}" \
                        --from-literal=password="\${MONGO_PASSWORD}" \
                        --save-config --dry-run=client -o yaml | kubectl apply -f -

                    kubectl create secret generic mongodb-keyfile \
                        --namespace shop \
                        --from-literal=keyfile="\${KEYFILE}" \
                        --save-config --dry-run=client -o yaml | kubectl apply -f -

                    kubectl create secret generic shop-secret \
                        --namespace shop \
                        --from-literal=JWT_SECRET="\${JWT_SECRET}" \
                        --from-literal=ADMIN_PASSWORD="\${ADMIN_PASSWORD}" \
                        --from-literal=SPRING_DATA_MONGODB_URI="mongodb://\${MONGO_USER}:\${MONGO_PASSWORD}@mongo-0.mongo-headless:27017,mongo-1.mongo-headless:27017,mongo-2.mongo-headless:27017/shop?authSource=admin&replicaSet=rs0" \
                        --save-config --dry-run=client -o yaml | kubectl apply -f -

                    # ── 4. Apply non-secret resources with pinned image tag ───────────
                    sed 's|${IMAGE_NAME}:latest|${IMAGE_NAME}:${IMAGE_TAG}|g' \
                        k8s/shop/deployment.yaml | kubectl apply -f -

                    kubectl apply -f k8s/shop/configmap.yaml
                    kubectl apply -f k8s/shop/service.yaml
                    kubectl apply -f k8s/shop/ingress.yaml
                """

                // Block until all pods pass their readiness probes.
                sh "kubectl rollout status deployment/shop --namespace shop --timeout=5m"
            }

            post {
                failure {
                    sh """
                        aws eks update-kubeconfig \
                            --name ${EKS_CLUSTER} \
                            --region ${AWS_REGION}
                        kubectl rollout undo deployment/shop --namespace shop || true
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
