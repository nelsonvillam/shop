#!/usr/bin/env bash
set -euo pipefail

AWS_REGION="${AWS_REGION:-sa-east-1}"
NAMESPACE="shop"
CONTEXT="docker-desktop"

echo "==> Switching to Docker Desktop Kubernetes context"
kubectl config use-context "$CONTEXT"

# ── External Secrets Operator ───────────────────────────────────────────────
echo "==> Installing External Secrets Operator (if not already installed)"
if ! helm status external-secrets -n external-secrets &>/dev/null 2>&1; then
  helm repo add external-secrets https://charts.external-secrets.io 2>/dev/null || true
  helm repo update
  helm install external-secrets external-secrets/external-secrets \
    -n external-secrets --create-namespace \
    --set installCRDs=true \
    --wait --timeout 120s
else
  echo "    ESO already installed, skipping"
fi

# ── nginx Ingress ────────────────────────────────────────────────────────────
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

# ── Kubernetes manifests ─────────────────────────────────────────────────────
echo "==> Applying manifests (k8s/overlays/local/)"
kubectl apply -k k8s/overlays/local/

# ── AWS credentials secret for ESO SecretStore ───────────────────────────────
echo "==> Creating aws-credentials secret for ESO (from local AWS CLI config)"
{ set +x
AWS_ACCESS_KEY_ID=$(aws configure get aws_access_key_id --profile "${AWS_PROFILE:-default}" 2>/dev/null \
  || echo "${AWS_ACCESS_KEY_ID:-}")
AWS_SECRET_ACCESS_KEY=$(aws configure get aws_secret_access_key --profile "${AWS_PROFILE:-default}" 2>/dev/null \
  || echo "${AWS_SECRET_ACCESS_KEY:-}")
AWS_SESSION_TOKEN=$(aws configure get aws_session_token --profile "${AWS_PROFILE:-default}" 2>/dev/null \
  || echo "${AWS_SESSION_TOKEN:-}")

if [ -z "$AWS_ACCESS_KEY_ID" ] || [ -z "$AWS_SECRET_ACCESS_KEY" ]; then
  echo "ERROR: AWS credentials not found. Run 'aws configure' or set AWS_ACCESS_KEY_ID/AWS_SECRET_ACCESS_KEY."
  exit 1
fi

kubectl create secret generic aws-credentials \
  --namespace "$NAMESPACE" \
  --from-literal=access-key-id="$AWS_ACCESS_KEY_ID" \
  --from-literal=secret-access-key="$AWS_SECRET_ACCESS_KEY" \
  --from-literal=session-token="${AWS_SESSION_TOKEN:-}" \
  --save-config --dry-run=client -o yaml | kubectl apply -f -
set +x; }

# ── Wait for ESO to sync secrets from AWS ───────────────────────────────────
echo "==> Waiting for ExternalSecrets to sync from AWS Secrets Manager"
for es in mongodb-credentials mongodb-keyfile shop-secret; do
  kubectl wait externalsecret/"$es" \
    --namespace "$NAMESPACE" \
    --for=condition=Ready \
    --timeout=60s
  echo "    ✓ $es synced"
done

# ── MongoDB ──────────────────────────────────────────────────────────────────
echo "==> Waiting for mongo-0 to be ready (this may take 2-3 minutes)"
kubectl wait --for=condition=ready pod/mongo-0 \
  --namespace "$NAMESPACE" --timeout=180s

echo "==> Initialising MongoDB replica set"
kubectl delete job mongodb-rs-init --namespace "$NAMESPACE" --ignore-not-found=true
kubectl apply -f k8s/base/mongodb/rs-init-job.yaml
kubectl wait --for=condition=complete job/mongodb-rs-init \
  --namespace "$NAMESPACE" --timeout=120s

# ── Shop deployment ──────────────────────────────────────────────────────────
echo "==> Waiting for shop deployment to be ready"
kubectl rollout status deployment/shop \
  --namespace "$NAMESPACE" --timeout=5m

# ── Port-forward ─────────────────────────────────────────────────────────────
echo ""
echo "==> Starting port-forward (Docker Desktop does not provision LoadBalancer IPs)"
pkill -f "kubectl port-forward" 2>/dev/null || true
sleep 1
kubectl port-forward -n ingress-nginx svc/ingress-nginx-controller 9090:80 &
sleep 2

echo ""
echo "✓ Deployment complete"
echo ""
echo "  Swagger UI:  http://localhost:9090/swagger-ui/index.html"
echo "  Health:      http://localhost:9090/actuator/health"
echo "  Zipkin:      kubectl port-forward svc/zipkin 9411:9411 -n shop"
echo ""
echo "  Watch pods:  kubectl get pods -n shop --watch"
echo "  Stop tunnel: pkill -f 'kubectl port-forward'"
