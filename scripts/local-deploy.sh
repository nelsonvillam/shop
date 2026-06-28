#!/usr/bin/env bash
set -euo pipefail

AWS_REGION="${AWS_REGION:-sa-east-1}"
NAMESPACE="shop"
CONTEXT="docker-desktop"

echo "==> Switching to Docker Desktop Kubernetes context"
kubectl config use-context "$CONTEXT"

echo "==> Installing nginx Ingress controller (if not already installed)"
if ! kubectl get deployment ingress-nginx-controller -n ingress-nginx &>/dev/null; then
  kubectl apply -f \
    https://raw.githubusercontent.com/kubernetes/ingress-nginx/controller-v1.10.0/deploy/static/provider/cloud/deploy.yaml
  kubectl wait --namespace ingress-nginx \
    --for=condition=ready pod \
    --selector=app.kubernetes.io/component=controller \
    --timeout=120s
else
  echo "    nginx already installed, skipping"
fi

echo "==> Applying namespace and non-secret resources"
kubectl apply -k k8s/overlays/local/

echo "==> Fetching secrets from AWS Secrets Manager (region: $AWS_REGION)"
{ set +x
MONGO_USER=$(aws secretsmanager get-secret-value \
  --secret-id shop/mongo-user --query SecretString --output text --region "$AWS_REGION")
MONGO_PASSWORD=$(aws secretsmanager get-secret-value \
  --secret-id shop/mongo-password --query SecretString --output text --region "$AWS_REGION")
ADMIN_PASSWORD=$(aws secretsmanager get-secret-value \
  --secret-id shop/admin-password --query SecretString --output text --region "$AWS_REGION")
JWT_SECRET=$(aws secretsmanager get-secret-value \
  --secret-id shop/jwt-secret --query SecretString --output text --region "$AWS_REGION")
KEYFILE=$(aws secretsmanager get-secret-value \
  --secret-id shop/mongodb-keyfile --query SecretString --output text --region "$AWS_REGION")

echo "==> Creating/updating Kubernetes secrets"
kubectl create secret generic mongodb-credentials \
  --namespace "$NAMESPACE" \
  --from-literal=username="$MONGO_USER" \
  --from-literal=password="$MONGO_PASSWORD" \
  --save-config --dry-run=client -o yaml | kubectl apply -f -

kubectl create secret generic mongodb-keyfile \
  --namespace "$NAMESPACE" \
  --from-literal=keyfile="$KEYFILE" \
  --save-config --dry-run=client -o yaml | kubectl apply -f -

kubectl create secret generic shop-secret \
  --namespace "$NAMESPACE" \
  --from-literal=JWT_SECRET="$JWT_SECRET" \
  --from-literal=ADMIN_PASSWORD="$ADMIN_PASSWORD" \
  --from-literal=SPRING_DATA_MONGODB_URI="mongodb://${MONGO_USER}:${MONGO_PASSWORD}@mongo-0.mongo-headless:27017,mongo-1.mongo-headless:27017,mongo-2.mongo-headless:27017/shop?authSource=admin&replicaSet=rs0" \
  --save-config --dry-run=client -o yaml | kubectl apply -f -
set +x; }

echo "==> Waiting for mongo-0 to be ready (this may take 2-3 minutes)"
kubectl wait --for=condition=ready pod/mongo-0 \
  --namespace "$NAMESPACE" --timeout=180s

echo "==> Initialising MongoDB replica set"
kubectl delete job mongodb-rs-init --namespace "$NAMESPACE" --ignore-not-found=true
kubectl apply -f k8s/mongodb/rs-init-job.yaml
kubectl wait --for=condition=complete job/mongodb-rs-init \
  --namespace "$NAMESPACE" --timeout=120s

echo "==> Waiting for shop deployment to be ready"
kubectl rollout status deployment/shop \
  --namespace "$NAMESPACE" --timeout=5m

echo ""
echo "✓ Deployment complete"
echo ""
echo "  Swagger UI:  http://localhost/swagger-ui/index.html"
echo "  Health:      http://localhost/actuator/health"
echo "  Zipkin:      kubectl port-forward svc/zipkin 9411:9411 -n shop"
echo ""
echo "  Watch pods:  kubectl get pods -n shop --watch"
