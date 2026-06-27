# ngrok Explained

This document explains what ngrok is, how it works, and how it is used in this project to expose a local Jenkins instance to GitHub webhooks.

---

## What is ngrok?

ngrok is a tool that creates a **secure tunnel** from a public URL on the internet to a port on your local machine. It acts as a reverse proxy — requests that arrive at the public ngrok URL are forwarded to your local service as if they came directly.

```
GitHub ──→ https://abc123.ngrok-free.app ──→ ngrok tunnel ──→ http://localhost:8080 (Jenkins)
```

Without ngrok, GitHub has no way to reach Jenkins because:
- Jenkins runs on `localhost` — only accessible from your own machine
- Your home/office network is behind a router with NAT — external traffic can't reach your computer directly
- Even if port forwarding was configured, your public IP changes frequently

ngrok solves all of this with a single command.

---

## Installation

```bash
brew install ngrok
```

---

## Account Setup (required for free tier)

ngrok requires a free account to start a session.

1. Sign up at **dashboard.ngrok.com/signup**
2. Get your auth token at **dashboard.ngrok.com/get-started/your-authtoken**
3. Save the token locally:

```bash
ngrok config add-authtoken <your-token>
```

This writes the token to `~/.config/ngrok/ngrok.yml`. You only need to do this once.

---

## Starting the Tunnel

```bash
ngrok http 8080
```

This exposes your local port `8080` (where Jenkins runs) to the internet. You will see output like:

```
Session Status                online
Account                       nelsonvillam@gmail.com (Plan: Free)
Version                       3.39.8
Region                        South America (sa)
Latency                       56ms
Web Interface                 http://127.0.0.1:4040
Forwarding                    https://glimpse-coasting-dismay.ngrok-free.app -> http://localhost:8080

Connections                   ttl     opn     rt1     rt5     p50     p90
                              8       0       0.01    0.01    30.07   30.53
```

| Field | Meaning |
|---|---|
| `Session Status` | Whether the tunnel is active |
| `Account` | The ngrok account being used |
| `Region` | The ngrok server region your tunnel connects through |
| `Latency` | Round-trip time between your machine and the ngrok server |
| `Web Interface` | Local URL for ngrok's inspection dashboard (see below) |
| `Forwarding` | The public URL that maps to your local Jenkins |

---

## The ngrok Inspection Dashboard

While ngrok is running, visit `http://127.0.0.1:4040` in your browser to see:

- All incoming HTTP requests in real time
- Request headers, body, and response status
- A **Replay** button to resend any previous request without triggering a new GitHub push

This is very useful for debugging webhook payloads.

---

## How It Works with Jenkins and GitHub

```
You push code
     │
     ▼
GitHub receives the push
     │
     ▼
GitHub sends POST to https://glimpse-coasting-dismay.ngrok-free.app/github-webhook/
     │
     ▼
ngrok receives the request at its server
     │
     ▼
ngrok forwards it through the tunnel to http://localhost:8080/github-webhook/
     │
     ▼
Jenkins receives the webhook and triggers the pipeline
```

### The `/github-webhook/` path

This is the endpoint Jenkins' GitHub plugin listens on. The full webhook Payload URL must always end with `/github-webhook/`:

```
https://glimpse-coasting-dismay.ngrok-free.app/github-webhook/
```

If the path is missing, GitHub posts to `/` which returns `403 Forbidden`.

---

## GitHub Webhook Configuration

In your GitHub repository → **Settings → Webhooks → Add webhook**:

| Field | Value |
|---|---|
| Payload URL | `https://<your-ngrok-subdomain>.ngrok-free.app/github-webhook/` |
| Content type | `application/json` |
| Secret | Optional — leave blank for local dev |
| Events | Just the push event |
| Active | Checked |

---

## Free Plan Limitations

| Limitation | Details |
|---|---|
| URL changes on restart | Every time you run `ngrok http 8080`, you get a different subdomain. You must update the GitHub webhook URL each time. |
| 1 static domain | Free accounts get one permanent subdomain (see below) |
| Connection limits | Limited requests per minute on the free plan |
| Single tunnel | Only one tunnel at a time on the free plan |

---

## Static Domain (Free)

The free plan includes one permanent static domain so the URL never changes.

1. Go to **dashboard.ngrok.com/domains**
2. Copy your free static domain (e.g. `your-name.ngrok-free.app`)
3. Start ngrok with that domain:

```bash
ngrok http --domain=your-name.ngrok-free.app 8080
```

With a static domain you only need to configure the GitHub webhook once — the URL never changes even after restarting ngrok.

---

## Important: ngrok Must Be Running

ngrok is not a background service — it must be running in a terminal whenever you want webhooks to work. If you close the terminal or stop ngrok:

- The tunnel goes offline
- GitHub webhook deliveries will fail
- Jenkins will not receive push events and the pipeline won't trigger automatically

You can verify the tunnel is active by checking the ngrok terminal output or visiting `http://127.0.0.1:4040`.

---

## Checking Webhook Delivery

After pushing code, you can verify the webhook was delivered in two places:

**ngrok terminal:**
```
POST /github-webhook/   200 OK
```

**GitHub → Settings → Webhooks → click your webhook → Recent Deliveries:**
- Green checkmark = delivered successfully
- Red X = delivery failed (click to see the error and redeliver)

---

## Summary

| Step | Command / Action |
|---|---|
| Install ngrok | `brew install ngrok` |
| Add auth token | `ngrok config add-authtoken <token>` |
| Start tunnel | `ngrok http 8080` |
| Use static domain | `ngrok http --domain=your-domain.ngrok-free.app 8080` |
| Inspect requests | Open `http://127.0.0.1:4040` |
| GitHub webhook URL | `https://<subdomain>.ngrok-free.app/github-webhook/` |
