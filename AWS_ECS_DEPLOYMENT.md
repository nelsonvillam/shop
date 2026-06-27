# Production Deployment on AWS (ECS + Atlas + ElastiCache + ALB)

This guide migrates the shop application from Docker Compose on a single EC2 instance to a fully managed AWS production setup.

---

## Architecture

```
[Internet]
     ↓ HTTPS :443
[ALB - Application Load Balancer]
     ↓ HTTP :8080
[ECS Fargate - shop container]
     ↓                    ↓
[MongoDB Atlas]    [ElastiCache Redis]
```

| Component | Service | Why |
|---|---|---|
| Spring Boot app | ECS Fargate | AWS manages restarts, scaling, and deployments |
| MongoDB | MongoDB Atlas | Managed database — backups, failover, scaling handled automatically |
| Redis | ElastiCache | Managed Redis — no maintenance |
| HTTPS / Load balancing | ALB + ACM | Distributes traffic, terminates SSL |

---

## Cost Estimate

| Service | Free tier | After free tier |
|---|---|---|
| ECS Fargate | No free tier | ~$15/month (0.5 vCPU, 1 GB) |
| MongoDB Atlas | M0 free forever | $57/month (M10) |
| ElastiCache | 750 hrs/month t3.micro | ~$12/month |
| ALB | No free tier | ~$16/month + traffic |
| ACM certificate | Free | Free |

**Total: ~$43/month** after free tiers expire.

---

## Step 1 — MongoDB Atlas

### 1.1 Create a free cluster

1. Go to **cloud.mongodb.com** → create account or log in
2. Click **Create a cluster** → choose **M0 Free**
3. Provider: **AWS**, Region: same as your EC2 (e.g. `sa-east-1`)
4. Name it `shop-cluster` → click **Create**

### 1.2 Create a database user

1. Go to **Database Access → Add New Database User**
2. Username: `shopuser`, Password: generate a strong one and save it
3. Role: **Read and write to any database**
4. Click **Add User**

### 1.3 Allow network access

1. Go to **Network Access → Add IP Address**
2. Click **Allow Access from Anywhere** (`0.0.0.0/0`) for now
   > You can restrict this to the ECS NAT Gateway IP after setup

### 1.4 Get the connection string

1. Go to **Database → Connect → Drivers**
2. Copy the connection string:

```
mongodb+srv://shopuser:<password>@shop-cluster.xxxxx.mongodb.net/shop?retryWrites=true&w=majority
```

Save this — it is used as `SPRING_DATA_MONGODB_URI` later.

---

## Step 2 — ElastiCache (Redis)

ElastiCache must live inside a VPC. ECS Fargate will run in the same VPC so they can communicate over the private network.

### 2.1 Create a subnet group

1. Go to **ElastiCache → Subnet Groups → Create**
2. Name: `shop-redis-subnet`
3. VPC: select the **default VPC**
4. Add all available subnets
5. Click **Create**

### 2.2 Create a security group for Redis

1. Go to **EC2 → Security Groups → Create Security Group**
2. Name: `shop-redis-sg`
3. Inbound rule: **Custom TCP**, Port `6379`, Source: `shop-ecs-sg` (created in Step 3 — come back and add this after)
4. Click **Create**

### 2.3 Create the Redis cluster

1. Go to **ElastiCache → Redis OSS → Create**
2. Name: `shop-redis`
3. Node type: `cache.t3.micro` (free tier eligible)
4. Number of replicas: **0**
5. Subnet group: `shop-redis-subnet`
6. Security group: `shop-redis-sg`
7. Click **Create**

Once created, copy the **Primary Endpoint**:

```
shop-redis.xxxxx.cache.amazonaws.com:6379
```

---

## Step 3 — ECS Fargate

### 3.1 Create an ECS cluster

1. Go to **ECS → Clusters → Create Cluster**
2. Name: `shop-cluster`
3. Infrastructure: **AWS Fargate** (serverless — no EC2 instances to manage)
4. Click **Create**

### 3.2 Create a task definition

1. Go to **ECS → Task Definitions → Create**
2. Name: `shop-task`
3. Launch type: **Fargate**
4. CPU: `0.5 vCPU`, Memory: `1 GB`
5. Under **Container**:
   - Name: `shop`
   - Image: `nelsonvillam/shop:latest`
   - Port: `8080`
6. Under **Environment variables**:

| Key | Value |
|---|---|
| `SPRING_PROFILES_ACTIVE` | `prod` |
| `SPRING_DATA_MONGODB_URI` | `mongodb+srv://shopuser:<pass>@shop-cluster.xxxxx.mongodb.net/shop?retryWrites=true&w=majority` |
| `REDIS_HOST` | `shop-redis.xxxxx.cache.amazonaws.com` |
| `REDIS_PORT` | `6379` |

> For production, store sensitive values in **AWS Secrets Manager** instead of plain text environment variables.

7. Click **Create**

### 3.3 Create a security group for ECS

1. Go to **EC2 → Security Groups → Create Security Group**
2. Name: `shop-ecs-sg`
3. Inbound rule: **Custom TCP**, Port `8080`, Source: `shop-alb-sg` (created in Step 4 — come back and add this after)
4. Click **Create**

Now go back to `shop-redis-sg` and add the inbound rule: TCP `6379` from `shop-ecs-sg`.

### 3.4 Create an ECS service

1. Go to **ECS → Clusters → shop-cluster → Create Service**
2. Launch type: **Fargate**
3. Task definition: `shop-task`
4. Service name: `shop-service`
5. Number of tasks: **1** (increase for scaling)
6. VPC: default VPC, select all subnets
7. Security group: `shop-ecs-sg`
8. Load balancer: skip for now — attach after Step 4
9. Click **Create**

---

## Step 4 — ALB + HTTPS

HTTPS requires a domain name. If you do not have one, the ALB can also be used with HTTP only.

### 4.1 Request an SSL certificate (free)

1. Go to **ACM (Certificate Manager) → Request Certificate**
2. Domain name: `shop.yourdomain.com`
3. Validation method: **DNS validation**
4. Click **Request**, then add the provided CNAME record to your domain's DNS
5. Wait for status to become **Issued** (usually a few minutes)

### 4.2 Create a security group for the ALB

1. Go to **EC2 → Security Groups → Create Security Group**
2. Name: `shop-alb-sg`
3. Inbound rules:
   - **HTTPS**, Port `443`, Source `0.0.0.0/0`
   - **HTTP**, Port `80`, Source `0.0.0.0/0` (for redirect to HTTPS)
4. Click **Create**

Now update `shop-ecs-sg` to allow port `8080` from `shop-alb-sg`.

### 4.3 Create the ALB

1. Go to **EC2 → Load Balancers → Create Load Balancer → Application Load Balancer**
2. Name: `shop-alb`
3. Scheme: **Internet-facing**
4. VPC: default, select all subnets
5. Security group: `shop-alb-sg`
6. Listeners:
   - **HTTP :80** → redirect to HTTPS :443
   - **HTTPS :443** → forward to target group
7. Certificate: select the one issued by ACM

### 4.4 Create a target group

1. Name: `shop-tg`
2. Target type: **IP** (required for Fargate)
3. Protocol: **HTTP**, Port: `8080`
4. Health check path: `/api/products`
5. Click **Create**

### 4.5 Attach the ALB to the ECS service

1. Go to **ECS → shop-cluster → shop-service → Update Service**
2. Under **Load balancing** → select the ALB
3. Container: `shop:8080`, Target group: `shop-tg`
4. Click **Update**

### 4.6 Point your domain to the ALB

Add a CNAME record in your DNS provider:

```
shop.yourdomain.com → shop-alb-xxxxx.sa-east-1.elb.amazonaws.com
```

The app will be live at:

```
https://shop.yourdomain.com/swagger-ui/index.html
```

---

## Step 5 — Update Jenkins to Deploy to ECS

Replace the `Deploy` stage in the Jenkinsfile with an ECS update command. ECS pulls the latest image and replaces the running task with zero downtime.

```groovy
stage('Deploy') {
    steps {
        sh """
            aws ecs update-service \
                --cluster shop-cluster \
                --service shop-service \
                --force-new-deployment \
                --region sa-east-1
        """
    }
}
```

### Prerequisites

- AWS CLI installed on the Jenkins host: `sudo apt install -y awscli`
- IAM user or role configured on Jenkins with the following permission:

```json
{
  "Effect": "Allow",
  "Action": "ecs:UpdateService",
  "Resource": "arn:aws:ecs:sa-east-1:<account-id>:service/shop-cluster/shop-service"
}
```

- AWS credentials configured on Jenkins: `aws configure` with the IAM user's access key and secret

---

## Security Groups Summary

| Security Group | Inbound | From |
|---|---|---|
| `shop-alb-sg` | 80, 443 | `0.0.0.0/0` (internet) |
| `shop-ecs-sg` | 8080 | `shop-alb-sg` |
| `shop-redis-sg` | 6379 | `shop-ecs-sg` |

Traffic flows in one direction: internet → ALB → ECS → ElastiCache. MongoDB Atlas is accessed over the internet from ECS (secured by Atlas network access rules).

---

## Comparison: EC2 + Docker vs ECS + Managed Services

| | EC2 + Docker Compose | ECS + Managed Services |
|---|---|---|
| App hosting | Docker on one EC2 | ECS Fargate (serverless) |
| MongoDB | Docker container | MongoDB Atlas |
| Redis | Docker container | ElastiCache |
| HTTPS | No | Yes (ACM + ALB) |
| Auto-restart | `restart: unless-stopped` | ECS restarts tasks automatically |
| Scaling | Manual | ECS auto-scaling policies |
| Backups | Manual | Automatic (Atlas + ElastiCache) |
| Cost | ~Free (t2.micro free tier) | ~$43/month |
