#!/bin/zsh
# Keep the public judge-demo tunnel alive. srv.us derives a stable URL from
# the SSH key, so reconnects preserve the shared link.
# The key lives in ~/.ssh so it survives temp-folder cleanup.
KEY="$HOME/.ssh/srvus_key"
LOG="$HOME/.x402-srvus.log"

while true; do
  echo "[$(date '+%Y-%m-%d %H:%M:%S')] connecting" >> "$LOG"
  ssh -i "$KEY" \
    -o StrictHostKeyChecking=no \
    -o UserKnownHostsFile=/dev/null \
    -o ServerAliveInterval=20 \
    -o ServerAliveCountMax=6 \
    -o ExitOnForwardFailure=yes \
    -R 1:localhost:8080 srv.us >> "$LOG" 2>&1
  echo "[$(date '+%Y-%m-%d %H:%M:%S')] tunnel exited; reconnecting in 2s" >> "$LOG"
  sleep 2
done
