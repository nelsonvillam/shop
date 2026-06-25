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

        stage('Build & Unit Test') {
            steps {
                sh './gradlew clean build -x integrationTest'
            }
            post {
                always {
                    junit '**/build/test-results/test/**/*.xml'
                }
            }
        }

        stage('Integration Test') {
            steps {
                sh './gradlew integrationTest'
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
