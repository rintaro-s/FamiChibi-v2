#!/bin/sh
# Deploy the FamiChibi host server to a VPS and run it as a systemd service on port 8010.
#   tools/deploy_vps.sh [user@host]        (default: ubuntu@133.242.50.151)
# Needs SSH access (key or password prompt) and sudo on the VPS.
set -eu
TARGET="${1:-ubuntu@133.242.50.151}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

echo "==> copying server + avatars to $TARGET:~/famichibi"
rsync -az --delete --exclude .venv --exclude data --exclude __pycache__ --exclude out \
  "$ROOT/server/" "$TARGET:famichibi/server/"
rsync -az "$ROOT/assets/" "$TARGET:famichibi/assets/"

echo "==> installing (venv, systemd, firewall)"
ssh -t "$TARGET" 'set -e
cd ~/famichibi/server
command -v python3 >/dev/null || sudo apt-get install -y python3
python3 -m venv .venv 2>/dev/null || { sudo apt-get update && sudo apt-get install -y python3-venv && python3 -m venv .venv; }
.venv/bin/pip install -q -r requirements.txt
sudo tee /etc/systemd/system/famichibi.service >/dev/null <<UNIT
[Unit]
Description=FamiChibi host server
After=network-online.target
[Service]
User=$USER
WorkingDirectory=$HOME/famichibi/server
Environment=FAMICHIBI_DATA=$HOME/famichibi/data
ExecStart=$HOME/famichibi/server/.venv/bin/python -m app.main --port 8010 --name "FamiChibi VPS"
Restart=always
[Install]
WantedBy=multi-user.target
UNIT
sudo systemctl daemon-reload
sudo systemctl enable --now famichibi
if command -v ufw >/dev/null && sudo ufw status | grep -q active; then sudo ufw allow 8010/tcp; fi
sleep 2; curl -s localhost:8010/api/info; echo'
echo "==> done. Also open TCP 8010 in the VPS provider's packet filter if it has one."
