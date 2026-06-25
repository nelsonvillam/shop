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
                sh './gradlew integrationTest --no-daemon'
            }
            post {
                always {
                    junit '**/build/test-results/integrationTest/**/*.xml'
                }
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
                sshagent(['server-ssh-key']) {
                    sh """
                        ssh -o StrictHostKeyChecking=no user@your-server '
                            docker pull ${IMAGE_NAME}:${IMAGE_TAG} &&
                            docker stop shop || true &&
                            docker rm shop || true &&
                            docker run -d \\
                                --name shop \\
                                --restart unless-stopped \\
                                -p 8080:8080 \\
                                -e SPRING_DATA_MONGODB_URI=mongodb://mongo:27017/shop \\
                                -e SPRING_REDIS_HOST=redis \\
                                -e SPRING_REDIS_PORT=6379 \\
                                ${IMAGE_NAME}:${IMAGE_TAG}
                        '
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
