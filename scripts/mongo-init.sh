#!/bin/bash
set -eo pipefail

# MongoDB 7 requires --keyFile when --replSet and --auth are both active.
# For a single-node replica set there are no other members to authenticate,
# so a fresh random key on each start is fine.
openssl rand -base64 32 > /tmp/mongodb.key
chown mongodb:mongodb /tmp/mongodb.key
chmod 400 /tmp/mongodb.key

exec docker-entrypoint.sh mongod \
    --replSet rs0 \
    --keyFile /tmp/mongodb.key \
    --bind_ip_all
