pipeline {
    agent any

    triggers {
        pollSCM('* * * * *')  // polls every minute
    }

    environment {
        IMAGE_NAME        = 'nelsonvillam/shop'
        IMAGE_TAG         = "${env.BUILD_NUMBER}"
        GRADLE_USER_HOME  = "${env.WORKSPACE}/.gradle"
        SONAR_USER_HOME   = "${env.WORKSPACE}/.sonar"
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
                    args "-e HOME=${env.WORKSPACE}"
                }
            }
            steps {
                sh './gradlew test jacocoTestReport --no-daemon'
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

        stage('Docker Build') {
            steps {
                sh "docker build -t ${IMAGE_NAME}:${IMAGE_TAG} ."
                sh "docker tag ${IMAGE_NAME}:${IMAGE_TAG} ${IMAGE_NAME}:latest"
            }
        }

        stage('Docker Push') {
            steps {
                sh "docker push ${IMAGE_NAME}:${IMAGE_TAG}"
                sh "docker push ${IMAGE_NAME}:latest"
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
        always {
            publishHTML(target: [
                reportName : 'Test Report',
                reportDir  : 'build/reports/tests/test',
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
            echo "Deployment of ${IMAGE_NAME}:${IMAGE_TAG} succeeded."
        }
        failure {
            echo "Pipeline failed. Check the logs above."
        }
    }
}
