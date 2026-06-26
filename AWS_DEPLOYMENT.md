# Deploying to AWS EC2

This guide deploys the shop application to an AWS free tier EC2 instance using Docker Compose. The Jenkins pipeline already pushes the image to Docker Hub — the EC2 instance just pulls and runs it.

---

## Prerequisites

- AWS free tier account
- Docker Hub image already pushed (`nelsonvillam/shop:latest`) — the Jenkins pipeline handles this automatically on every push to `main`

---

## Step 1 — Create the EC2 Instance

1. Go to **AWS Console → EC2 → Launch Instance**
2. Fill in the settings:

| Setting | Value |
|---|---|
| Name | `shop-server` |
| AMI | **Ubuntu Server 22.04 LTS** (free tier eligible) |
| Instance type | **t2.micro** (free tier — 1 vCPU, 1 GB RAM) |
| Key pair | Create new → name it `shop-key` → download the `.pem` file |

3. Under **Network settings → Security Group**, create a new one with these inbound rules:

| Type | Port | Source | Purpose |
|---|---|---|---|
| SSH | 22 | My IP | Terminal access |
| Custom TCP | 8081 | 0.0.0.0/0 | Spring Boot app |

4. Under **Configure storage** → keep the default 8 GB (or increase to 30 GB — still free tier)

5. Click **Launch Instance**

---

## Step 2 — Connect to the Instance

Wait ~1 minute for the instance to start, then copy the **Public IPv4 address** from the EC2 dashboard.

```bash
# Fix key permissions (required by SSH)
chmod 400 ~/Downloads/shop-key.pem

# Connect
ssh -i ~/Downloads/shop-key.pem ubuntu@<your-public-ip>
```

---

## Step 3 — Install Docker

Run these commands on the EC2 instance:

```bash
# Update packages
sudo apt update && sudo apt upgrade -y

# Install Docker
sudo apt install -y docker.io

# Start Docker and enable it on boot
sudo systemctl start docker
sudo systemctl enable docker

# Allow the ubuntu user to run Docker without sudo
sudo usermod -aG docker ubuntu

# Apply the group change without logging out
newgrp docker
```

Verify:

```bash
docker --version
```

---

## Step 4 — Install Docker Compose

```bash
sudo curl -L "https://github.com/docker/compose/releases/latest/download/docker-compose-$(uname -s)-$(uname -m)" \
  -o /usr/local/bin/docker-compose

sudo chmod +x /usr/local/bin/docker-compose

docker-compose --version
```

---

## Step 5 — Create the docker-compose.yml

```bash
mkdir ~/shop && cd ~/shop
nano docker-compose.yml
```

Paste this content:

```yaml
services:
  mongo:
    image: mongo:7
    restart: unless-stopped
    environment:
      MONGO_INITDB_ROOT_USERNAME: ${MONGO_USER}
      MONGO_INITDB_ROOT_PASSWORD: ${MONGO_PASSWORD}
      MONGO_INITDB_DATABASE: shop
    volumes:
      - mongo-data:/data/db

  redis:
    image: redis:7-alpine
    restart: unless-stopped
    volumes:
      - redis-data:/data

  shop:
    image: nelsonvillam/shop:latest
    restart: unless-stopped
    ports:
      - "8081:8080"
    environment:
      SPRING_DATA_MONGODB_URI: mongodb://${MONGO_USER}:${MONGO_PASSWORD}@mongo:27017/shop?authSource=admin
      SPRING_REDIS_HOST: redis
      SPRING_REDIS_PORT: 6379
    depends_on:
      - mongo
      - redis

volumes:
  mongo-data:
  redis-data:
```

---

## Step 6 — Set the Credentials

Create a `.env` file in the same folder. Docker Compose reads it automatically at startup.

```bash
nano ~/shop/.env
```

```
MONGO_USER=admin
MONGO_PASSWORD=yourStrongPassword
```

> Never commit this file to Git. It contains secrets.

---

## Step 7 — Start the Application

```bash
cd ~/shop
docker-compose up -d
```

Check that all three containers are running:

```bash
docker-compose ps
```

Check the app logs:

```bash
docker-compose logs -f shop
```

---

## Step 8 — Test It

From your local machine:

```bash
curl http://<your-public-ip>:8081/api/products
```

Or open in a browser:

```
http://<your-public-ip>:8081/swagger-ui/index.html
```

---

## Updating the App

Every time Jenkins pushes a new image to Docker Hub, SSH into the EC2 instance and run:

```bash
cd ~/shop
docker-compose pull shop
docker-compose up -d
```

This pulls the latest image and restarts only the `shop` container — MongoDB and Redis keep running and data is preserved.

> To automate this, add a Deploy stage to the Jenkinsfile that SSHs into the EC2 instance and runs these two commands. That requires storing the EC2 SSH key in Jenkins credentials.

---

## Free Tier Limits

| Resource | Free tier allowance | Notes |
|---|---|---|
| EC2 hours | 750 hrs/month | t2.micro running 24/7 = ~744 hrs — fits exactly |
| EBS storage | 30 GB | Default 8 GB is sufficient |
| Data transfer out | 100 GB/month | Only relevant under heavy traffic |

---

## Swap Space (Recommended)

t2.micro has only 1 GB RAM. Running Spring Boot + MongoDB + Redis together is tight — MongoDB alone can use 500 MB. Adding swap prevents the instance from running out of memory:

```bash
sudo fallocate -l 1G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile

# Make swap persist across reboots
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
```

Verify:

```bash
free -h
```

---

## Troubleshooting

| Problem | Cause | Fix |
|---|---|---|
| Cannot SSH | Key permissions too open | Run `chmod 400 shop-key.pem` |
| Port 8081 not reachable | Security group misconfigured | Add inbound rule for TCP 8081 in the AWS console |
| App fails to start | Not enough memory | Add swap space (see above) |
| MongoDB auth error | Wrong credentials in `.env` | Check `MONGO_USER` and `MONGO_PASSWORD` match what MongoDB was initialized with |
| Image not found | Docker Hub image not pushed | Run the Jenkins pipeline first to push `nelsonvillam/shop:latest` |
