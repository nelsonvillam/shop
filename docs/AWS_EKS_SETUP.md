# AWS EKS Cluster Setup Guide

This guide explains how to create a production-ready EKS cluster from scratch using the AWS CLI and `eksctl`. It covers IAM policies, VPC options, node groups, kubectl access, Secrets Manager integration, and cluster add-ons.

---

## What is EKS

Amazon Elastic Kubernetes Service (EKS) is a managed Kubernetes control plane. AWS runs the API server, etcd, and scheduler for you — you only manage the worker nodes that run your workloads.

```
┌──────────────────────────────────────────────────────┐
│  AWS Managed Control Plane (EKS)                     │
│  kube-apiserver · etcd · kube-scheduler              │
│  kube-controller-manager · cloud-controller-manager  │
└────────────────────┬─────────────────────────────────┘
                     │  Kubernetes API
        ┌────────────┴────────────┐
        │                         │
┌───────▼──────┐         ┌────────▼─────┐
│  Node (EC2)  │         │  Node (EC2)  │  ← you manage these
│  kubelet     │         │  kubelet     │
│  kube-proxy  │         │  kube-proxy  │
│  [your pods] │         │  [your pods] │
└──────────────┘         └──────────────┘
```

---

## Prerequisites

Install the following tools before starting.

### AWS CLI

```bash
# macOS
brew install awscli

# Linux
curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o awscliv2.zip
unzip awscliv2.zip && sudo ./aws/install

# Verify
aws --version
```

### eksctl

`eksctl` is the official CLI for creating and managing EKS clusters. It wraps dozens of AWS API calls into a single command.

```bash
# macOS
brew tap weaveworks/tap
brew install weaveworks/tap/eksctl

# Linux
curl --silent --location \
  "https://github.com/weaveworks/eksctl/releases/latest/download/eksctl_$(uname -s)_amd64.tar.gz" \
  | tar xz -C /tmp
sudo mv /tmp/eksctl /usr/local/bin

# Verify
eksctl version
```

### kubectl

```bash
# macOS
brew install kubectl

# Linux
curl -LO "https://dl.k8s.io/release/$(curl -sL https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl"
sudo install -o root -g root -m 0755 kubectl /usr/local/bin/kubectl

# Verify
kubectl version --client
```

### Configure AWS credentials

```bash
aws configure
# AWS Access Key ID:     <your-access-key>
# AWS Secret Access Key: <your-secret-key>
# Default region name:   sa-east-1
# Default output format: json
```

Verify identity:

```bash
aws sts get-caller-identity
```

---

## IAM Permissions

The IAM user or role running `eksctl` needs a broad set of permissions. The simplest approach for a learning environment is to attach **AdministratorAccess**, but in a real environment you should scope it down.

### Minimum policy for eksctl

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "eks:*",
        "ec2:*",
        "iam:CreateRole",
        "iam:DeleteRole",
        "iam:AttachRolePolicy",
        "iam:DetachRolePolicy",
        "iam:PutRolePolicy",
        "iam:DeleteRolePolicy",
        "iam:GetRole",
        "iam:GetRolePolicy",
        "iam:ListRoles",
        "iam:ListAttachedRolePolicies",
        "iam:PassRole",
        "iam:CreateInstanceProfile",
        "iam:DeleteInstanceProfile",
        "iam:AddRoleToInstanceProfile",
        "iam:RemoveRoleFromInstanceProfile",
        "iam:GetInstanceProfile",
        "iam:ListInstanceProfiles",
        "iam:ListInstanceProfilesForRole",
        "iam:CreateOpenIDConnectProvider",
        "iam:DeleteOpenIDConnectProvider",
        "iam:GetOpenIDConnectProvider",
        "iam:ListOpenIDConnectProviders",
        "iam:TagOpenIDConnectProvider",
        "cloudformation:*",
        "autoscaling:*",
        "elasticloadbalancing:*",
        "ssm:GetParameter"
      ],
      "Resource": "*"
    }
  ]
}
```

Attach this policy to your IAM user:

```bash
# Create the policy
aws iam create-policy \
  --policy-name EksctlPolicy \
  --policy-document file://eksctl-policy.json

# Attach to your user
aws iam attach-user-policy \
  --user-name <your-iam-user> \
  --policy-arn arn:aws:iam::<account-id>:policy/EksctlPolicy
```

### Minimum policy for Jenkins to deploy

The machine running Jenkins (or any CI/CD tool) needs these permissions:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "eks:DescribeCluster"
      ],
      "Resource": "arn:aws:eks:<region>:<account-id>:cluster/<cluster-name>"
    },
    {
      "Effect": "Allow",
      "Action": [
        "secretsmanager:GetSecretValue",
        "secretsmanager:DescribeSecret"
      ],
      "Resource": "arn:aws:secretsmanager:<region>:<account-id>:secret:shop/*"
    }
  ]
}
```

---

## VPC Options

EKS nodes must run inside a VPC. You have two options.

### Option A — Use the default VPC (quick start)

Every AWS account has a default VPC in each region with public subnets in all availability zones. `eksctl` detects and uses it automatically.

```bash
# List your default VPC
aws ec2 describe-vpcs \
  --filters "Name=isDefault,Values=true" \
  --query 'Vpcs[*].{VpcId:VpcId,CidrBlock:CidrBlock}' \
  --output table

# List its subnets
aws ec2 describe-subnets \
  --filters "Name=defaultForAz,Values=true" \
  --query 'Subnets[*].{SubnetId:SubnetId,AZ:AvailabilityZone,CIDR:CidrBlock}' \
  --output table
```

**Limitation:** the default VPC uses public subnets only. Nodes get public IPs, which is fine for development but not recommended for production.

### Option B — Create a dedicated VPC (production)

For production, create a VPC with both public subnets (for load balancers) and private subnets (for nodes). Use the AWS VPC console wizard or CloudFormation:

```bash
# Deploy the AWS-recommended EKS VPC template
aws cloudformation create-stack \
  --stack-name eks-vpc \
  --template-url https://s3.us-west-2.amazonaws.com/amazon-eks/cloudformation/2020-10-29/amazon-eks-vpc-private-subnets.yaml \
  --parameters \
      ParameterKey=VpcBlock,ParameterValue=192.168.0.0/16

# Wait for completion
aws cloudformation wait stack-create-complete --stack-name eks-vpc

# Get the output values (subnet IDs, VPC ID)
aws cloudformation describe-stacks \
  --stack-name eks-vpc \
  --query 'Stacks[0].Outputs' \
  --output table
```

---

## Creating the Cluster

### Method 1 — eksctl (recommended)

`eksctl create cluster` creates the control plane, node group, IAM roles, security groups, and updates your `~/.kube/config` in a single command.

#### Using the default VPC

```bash
eksctl create cluster \
  --name shop-cluster \
  --region sa-east-1 \
  --nodegroup-name shop-nodes \
  --node-type t3.medium \
  --nodes 2 \
  --nodes-min 1 \
  --nodes-max 3 \
  --managed
```

#### Using existing subnets (avoids creating a new VPC)

```bash
# First get your subnet IDs
aws ec2 describe-subnets \
  --filters "Name=defaultForAz,Values=true" \
  --region sa-east-1 \
  --query 'Subnets[*].SubnetId' \
  --output text

eksctl create cluster \
  --name shop-cluster \
  --region sa-east-1 \
  --nodegroup-name shop-nodes \
  --node-type t3.medium \
  --nodes 2 \
  --nodes-min 1 \
  --nodes-max 3 \
  --managed \
  --vpc-public-subnets subnet-aaa,subnet-bbb,subnet-ccc
```

#### Using a config file (recommended for reproducibility)

Save as `cluster.yaml`:

```yaml
apiVersion: eksctl.io/v1alpha5
kind: ClusterConfig

metadata:
  name: shop-cluster
  region: sa-east-1
  version: "1.34"

managedNodeGroups:
  - name: shop-nodes
    instanceType: t3.medium
    minSize: 1
    maxSize: 3
    desiredCapacity: 2
    volumeSize: 20
    privateNetworking: false   # set true when using private subnets
    iam:
      withAddonPolicies:
        autoScaler: true

addons:
  - name: vpc-cni
  - name: coredns
  - name: kube-proxy
  - name: metrics-server
```

```bash
eksctl create cluster -f cluster.yaml
```

#### What eksctl creates automatically

| Resource | Description |
|---|---|
| CloudFormation stack (control plane) | EKS cluster, IAM roles, security groups |
| CloudFormation stack (node group) | EC2 Auto Scaling Group, launch template |
| IAM role for the cluster | `AmazonEKSClusterPolicy` attached |
| IAM role for the node group | `AmazonEKSWorkerNodePolicy`, `AmazonEKS_CNI_Policy`, `AmazonEC2ContainerRegistryReadOnly` attached |
| `~/.kube/config` entry | Adds the cluster context and sets it as current |

The whole process takes **12–20 minutes**.

---

### Method 2 — AWS CLI only (manual, educational)

This shows every resource `eksctl` creates for you automatically.

#### Step 1 — Create the cluster IAM role

```bash
# Trust policy — allows EKS to assume this role
cat > eks-cluster-trust.json <<'EOF'
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": { "Service": "eks.amazonaws.com" },
    "Action": "sts:AssumeRole"
  }]
}
EOF

aws iam create-role \
  --role-name ShopEksClusterRole \
  --assume-role-policy-document file://eks-cluster-trust.json

aws iam attach-role-policy \
  --role-name ShopEksClusterRole \
  --policy-arn arn:aws:iam::aws:policy/AmazonEKSClusterPolicy
```

#### Step 2 — Create the cluster

```bash
CLUSTER_ROLE_ARN=$(aws iam get-role \
  --role-name ShopEksClusterRole \
  --query 'Role.Arn' --output text)

aws eks create-cluster \
  --name shop-cluster \
  --region sa-east-1 \
  --kubernetes-version 1.34 \
  --role-arn $CLUSTER_ROLE_ARN \
  --resources-vpc-config \
      subnetIds=subnet-aaa,subnet-bbb,subnet-ccc,\
      securityGroupIds=sg-xxx,\
      endpointPublicAccess=true,\
      endpointPrivateAccess=false

# Wait until ACTIVE (takes ~10 min)
aws eks wait cluster-active --name shop-cluster --region sa-east-1
```

#### Step 3 — Create the node group IAM role

```bash
cat > eks-node-trust.json <<'EOF'
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": { "Service": "ec2.amazonaws.com" },
    "Action": "sts:AssumeRole"
  }]
}
EOF

aws iam create-role \
  --role-name ShopEksNodeRole \
  --assume-role-policy-document file://eks-node-trust.json

# Three required policies for worker nodes
aws iam attach-role-policy \
  --role-name ShopEksNodeRole \
  --policy-arn arn:aws:iam::aws:policy/AmazonEKSWorkerNodePolicy

aws iam attach-role-policy \
  --role-name ShopEksNodeRole \
  --policy-arn arn:aws:iam::aws:policy/AmazonEKS_CNI_Policy

aws iam attach-role-policy \
  --role-name ShopEksNodeRole \
  --policy-arn arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryReadOnly
```

#### Step 4 — Create the managed node group

```bash
NODE_ROLE_ARN=$(aws iam get-role \
  --role-name ShopEksNodeRole \
  --query 'Role.Arn' --output text)

aws eks create-nodegroup \
  --cluster-name shop-cluster \
  --region sa-east-1 \
  --nodegroup-name shop-nodes \
  --node-role $NODE_ROLE_ARN \
  --subnets subnet-aaa subnet-bbb subnet-ccc \
  --instance-types t3.medium \
  --scaling-config minSize=1,maxSize=3,desiredSize=2 \
  --disk-size 20 \
  --ami-type AL2023_x86_64_STANDARD

# Wait until ACTIVE (takes ~5 min)
aws eks wait nodegroup-active \
  --cluster-name shop-cluster \
  --nodegroup-name shop-nodes \
  --region sa-east-1
```

---

## Configuring kubectl

After the cluster is created, point `kubectl` at it:

```bash
aws eks update-kubeconfig \
  --name shop-cluster \
  --region sa-east-1

# Verify
kubectl get nodes
```

### How it works

`aws eks update-kubeconfig` writes a new context into `~/.kube/config`. The kubeconfig uses the `aws` CLI as a credential plugin — every `kubectl` call runs `aws eks get-token` behind the scenes to get a short-lived token signed by your IAM identity.

```yaml
# ~/.kube/config (simplified)
users:
  - name: arn:aws:eks:sa-east-1:660743084735:cluster/shop-cluster
    user:
      exec:
        command: aws
        args: ["eks", "get-token", "--cluster-name", "shop-cluster"]
```

This means `kubectl` access is controlled by IAM — whoever has `eks:DescribeCluster` permission can authenticate.

---

## Granting Other IAM Users Cluster Access

By default, only the IAM identity that created the cluster has access. To grant access to other users or roles, edit the `aws-auth` ConfigMap in the cluster:

```bash
# View current mappings
kubectl describe configmap aws-auth --namespace kube-system

# Open the ConfigMap for editing
kubectl edit configmap aws-auth --namespace kube-system
```

Add entries under `mapUsers` or `mapRoles`:

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: aws-auth
  namespace: kube-system
data:
  mapUsers: |
    - userarn: arn:aws:iam::660743084735:user/AnotherUser
      username: another-user
      groups:
        - system:masters          # full cluster admin
  mapRoles: |
    - rolearn: arn:aws:iam::660743084735:role/JenkinsRole
      username: jenkins
      groups:
        - system:masters
```

**Common groups:**

| Group | Kubernetes ClusterRole | Permissions |
|---|---|---|
| `system:masters` | `cluster-admin` | Full access to everything |
| `system:authenticated` | — | Basic authenticated user |
| Custom group | Bound by a `ClusterRoleBinding` you create | Scoped as needed |

Or use `eksctl` to do it without editing YAML manually:

```bash
# Grant an IAM user cluster-admin
eksctl create iamidentitymapping \
  --cluster shop-cluster \
  --region sa-east-1 \
  --arn arn:aws:iam::660743084735:user/AnotherUser \
  --username another-user \
  --group system:masters

# Grant an IAM role
eksctl create iamidentitymapping \
  --cluster shop-cluster \
  --region sa-east-1 \
  --arn arn:aws:iam::660743084735:role/JenkinsRole \
  --username jenkins \
  --group system:masters
```

---

## AWS Secrets Manager Integration

Store sensitive values in AWS Secrets Manager — never in environment files or Git.

### Creating secrets

```bash
# Simple string value
aws secretsmanager create-secret \
  --name shop/mongo-user \
  --description "MongoDB username" \
  --secret-string "shopuser" \
  --region sa-east-1

# Generated random secret
aws secretsmanager create-secret \
  --name shop/mongo-password \
  --description "MongoDB password" \
  --secret-string "$(openssl rand -base64 32)" \
  --region sa-east-1

# Binary-safe secret (keyfile, certificates)
aws secretsmanager create-secret \
  --name shop/mongodb-keyfile \
  --description "MongoDB RS keyfile" \
  --secret-string "$(openssl rand -base64 756)" \
  --region sa-east-1
```

### Reading secrets

```bash
# Get the secret value
aws secretsmanager get-secret-value \
  --secret-id shop/mongo-user \
  --query SecretString \
  --output text \
  --region sa-east-1

# Store in a shell variable (used in CI/CD scripts)
MONGO_USER=$(aws secretsmanager get-secret-value \
  --secret-id shop/mongo-user \
  --query SecretString \
  --output text \
  --region sa-east-1)
```

### Rotating secrets

```bash
aws secretsmanager update-secret \
  --secret-id shop/mongo-password \
  --secret-string "$(openssl rand -base64 32)" \
  --region sa-east-1
```

The next CI/CD pipeline run will pick up the new value automatically, since secrets are fetched at deploy time.

### Required IAM permissions for secret access

```json
{
  "Effect": "Allow",
  "Action": [
    "secretsmanager:GetSecretValue",
    "secretsmanager:DescribeSecret"
  ],
  "Resource": "arn:aws:secretsmanager:sa-east-1:660743084735:secret:shop/*"
}
```

The trailing `/*` grants access to all secrets whose names start with `shop/`. Use more specific ARNs in production.

---

## Installing the nginx Ingress Controller

EKS does not include an Ingress Controller by default. Install nginx to handle external HTTP traffic.

```bash
kubectl apply -f \
  https://raw.githubusercontent.com/kubernetes/ingress-nginx/controller-v1.10.0/deploy/static/provider/aws/deploy.yaml

# Wait until the controller pod is running
kubectl wait --namespace ingress-nginx \
  --for=condition=ready pod \
  --selector=app.kubernetes.io/component=controller \
  --timeout=120s

# Get the external hostname (AWS creates a Classic or NLB load balancer)
kubectl get service ingress-nginx-controller \
  --namespace ingress-nginx \
  --output jsonpath='{.status.loadBalancer.ingress[0].hostname}'
```

Point your DNS record (e.g. `shop.yourdomain.com`) to this hostname using a CNAME.

---

## Choosing Node Instance Types

| Type | vCPU | RAM | Use case |
|---|---|---|---|
| `t3.small` | 2 | 2 GiB | Single lightweight service |
| `t3.medium` | 2 | 4 GiB | General purpose (recommended for this project) |
| `t3.large` | 2 | 8 GiB | MongoDB + Redis + app on fewer nodes |
| `m5.xlarge` | 4 | 16 GiB | Production workloads |

**Rule of thumb:** add up all pod memory requests, add ~500 MiB per node for system overhead, then pick an instance type where 2 nodes cover the total with headroom.

For this project (MongoDB × 3 + shop × 2 + Redis + Zipkin):
- Total requests: ~1.5 CPU, ~2.9 GiB memory
- 2 × `t3.medium` (allocatable: ~3.4 CPU, ~6.8 GiB) gives ~2× headroom ✓

---

## Cluster Costs

EKS pricing in `sa-east-1` (approximate, check AWS pricing for current rates):

| Resource | Cost |
|---|---|
| EKS control plane | ~$0.10/hour (~$73/month) |
| 2 × t3.medium nodes | ~$0.052/hour each (~$76/month for both) |
| EBS volumes (PVCs) | ~$0.10/GiB-month |
| Load balancer (Ingress) | ~$0.025/hour + data transfer |
| **Total estimate** | **~$155/month** |

> EKS has no free tier. Delete the cluster when not in use to avoid charges (see [Cleanup](#cleanup)).

---

## Useful Commands

```bash
# Check cluster status
aws eks describe-cluster --name shop-cluster --region sa-east-1 \
  --query 'cluster.{Status:status,Version:version,Endpoint:endpoint}'

# List node groups
aws eks list-nodegroups --cluster-name shop-cluster --region sa-east-1

# Scale node group (0 nodes = cluster costs only control plane)
aws eks update-nodegroup-config \
  --cluster-name shop-cluster \
  --nodegroup-name shop-nodes \
  --region sa-east-1 \
  --scaling-config minSize=0,maxSize=3,desiredSize=0

# View all running pods across namespaces
kubectl get pods --all-namespaces

# View cluster events (useful for debugging)
kubectl get events --namespace shop --sort-by='.lastTimestamp'

# Check node resource usage
kubectl top nodes

# Check pod resource usage
kubectl top pods --namespace shop
```

---

## Cleanup

Deleting the cluster removes all nodes, load balancers, and CloudFormation stacks. Persistent volumes (EBS) are deleted separately.

```bash
# Delete the cluster and node group
eksctl delete cluster \
  --name shop-cluster \
  --region sa-east-1

# Verify all CloudFormation stacks are gone
aws cloudformation list-stacks \
  --region sa-east-1 \
  --stack-status-filter CREATE_COMPLETE \
  --query 'StackSummaries[?contains(StackName, `shop-cluster`)].StackName'

# Delete Secrets Manager secrets (if no longer needed)
aws secretsmanager delete-secret --secret-id shop/mongo-user --region sa-east-1
aws secretsmanager delete-secret --secret-id shop/mongo-password --region sa-east-1
aws secretsmanager delete-secret --secret-id shop/admin-password --region sa-east-1
aws secretsmanager delete-secret --secret-id shop/jwt-secret --region sa-east-1
aws secretsmanager delete-secret --secret-id shop/mongodb-keyfile --region sa-east-1
```

---

## Troubleshooting

| Problem | Cause | Fix |
|---|---|---|
| `eksctl create cluster` fails with `ValidationError` | IAM user lacks permissions | Attach the `EksctlPolicy` shown in [IAM Permissions](#iam-permissions) |
| `kubectl get nodes` returns `Unauthorized` | kubeconfig not updated | Run `aws eks update-kubeconfig --name shop-cluster --region sa-east-1` |
| Another IAM user can't access the cluster | Not added to `aws-auth` | Run `eksctl create iamidentitymapping` (see [Granting Other IAM Users](#granting-other-iam-users-cluster-access)) |
| Nodes stuck in `NotReady` | VPC/subnet tag missing | Add tag `kubernetes.io/cluster/<cluster-name>=owned` to subnets |
| Pods stuck in `Pending` | Not enough CPU/memory on nodes | Scale up node group or use larger instance type |
| `ImagePullBackOff` | Docker Hub rate limit | Add Docker Hub credentials as a K8s `Secret` of type `kubernetes.io/dockerconfigjson` |
| Load balancer hostname not resolving | DNS propagation delay | Wait 2–5 minutes after the LB is created |
| `secretsmanager:GetSecretValue` denied | IAM policy not attached | Attach the Secrets Manager policy to the IAM user or role making the call |
| EKS cluster creation hangs | CloudFormation rollback in progress | Check AWS Console → CloudFormation for the error event |
