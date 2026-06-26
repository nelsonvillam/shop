pipeline {
    agent any

    environment {
        IMAGE_NAME = 'nelsonvillam/shop'
        IMAGE_TAG  = "${env.BUILD_NUMBER}"
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Unit Test') {
            agent {
                docker {
                    image 'eclipse-temurin:21-jdk'
                    reuseNode true
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
            agent {
                docker {
                    image 'eclipse-temurin:21-jdk'
                    reuseNode true
                    args '-v /var/run/docker.sock:/var/run/docker.sock -u root'
                }
            }
            steps {
                withEnv([
                    'TESTCONTAINERS_RYUK_DISABLED=true',
                    'TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal'
                ]) {
                    sh '''
                        echo "=== /etc/hosts ==="
                        cat /etc/hosts
                        echo "=== routing table ==="
                        cat /proc/net/route
                        ./gradlew integrationTest --no-daemon
                    '''
                }
            }
            post {
                always {
                    junit '**/build/test-results/integrationTest/**/*.xml'
                }
            }
        }

        stage('SonarQube Analysis') {
            agent {
                docker {
                    image 'eclipse-temurin:21-jdk'
                    reuseNode true
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
                timeout(time: 1, unit: 'MINUTES') {
                    waitForQualityGate abortPipeline: true
                }
            }
        }

        stage('Build') {
            agent {
                docker {
                    image 'eclipse-temurin:21-jdk'
                    reuseNode true
                }
            }
            steps {
                sh './gradlew bootJar --no-daemon'
            }
        }

        stage('Docker Build') {
            steps {
                sh "docker build -t ${IMAGE_NAME}:${IMAGE_TAG} ."
                sh "docker tag ${IMAGE_NAME}:${IMAGE_TAG} ${IMAGE_NAME}:latest"
            }
        }

        stage('Docker Push') {
            steps {
                withCredentials([usernamePassword(
                    credentialsId: 'dockerhub-creds',
                    usernameVariable: 'DOCKER_USER',
                    passwordVariable: 'DOCKER_PASS'
                )]) {
                    sh 'echo $DOCKER_PASS | docker login -u $DOCKER_USER --password-stdin'
                    sh "docker push ${IMAGE_NAME}:${IMAGE_TAG}"
                    sh "docker push ${IMAGE_NAME}:latest"
                }
            }
        }

        stage('Deploy') {
            steps {
                withCredentials([
                    string(credentialsId: 'mongo-user',     variable: 'MONGO_USER'),
                    string(credentialsId: 'mongo-password', variable: 'MONGO_PASSWORD')
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
    }

    post {
        success {
            echo "Deployment of ${IMAGE_NAME}:${IMAGE_TAG} succeeded."
        }
        failure {
            echo "Pipeline failed. Check the logs above."
        }
    }
}
