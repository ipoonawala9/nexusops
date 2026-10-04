#!/bin/sh
# Every published port must bind to loopback: the dev stack uses well-known passwords.
set -eu
cd "$(dirname "$0")/.."
bad=$(docker compose -f docker-compose.yml --profile app config --format json \
  | python3 -c 'import json,sys
d=json.load(sys.stdin)
for name,svc in d["services"].items():
    for p in svc.get("ports",[]):
        if p.get("host_ip") != "127.0.0.1": print(name, p.get("published"))')
if [ -n "$bad" ]; then echo "Ports not bound to 127.0.0.1:"; echo "$bad"; exit 1; fi
echo "all published ports bound to 127.0.0.1"
