"""Builds install-server.sh: one self-contained script that sets up the server on a fresh Ubuntu machine.

Usage: python make_installer.py > install-server.sh
The server files are embedded, so the machine needs no access to this repository.
"""

import base64
import io
import tarfile
from pathlib import Path

FILES = ["app/__init__.py", "app/main.py", "requirements.txt", "Dockerfile", "Caddyfile", "docker-compose.yml"]

SCRIPT = r"""#!/usr/bin/env bash
# VehicleMapping server installer. Safe to run again: it keeps the existing token and data.
set -euo pipefail

DIR="$HOME/vehiclemapping-server"
mkdir -p "$DIR"
cd "$DIR"

echo "==> Copio i file del server in $DIR"
base64 -d <<'PAYLOAD' | tar xz
@PAYLOAD@
PAYLOAD

if ! command -v docker >/dev/null 2>&1; then
    echo "==> Installo Docker"
    curl -fsSL https://get.docker.com | sudo sh
fi

if [ ! -f .env ]; then
    IP="$(curl -fsS https://checkip.amazonaws.com | tr -d '[:space:]')"
    {
        echo "VM_API_TOKEN=$(openssl rand -hex 32)"
        echo "VM_DOMAIN=${IP//./-}.sslip.io"
    } > .env
    chmod 600 .env
fi

echo "==> Avvio il server"
sudo docker compose up -d --build

. ./.env
echo "==> Attendo il certificato HTTPS (fino a 2 minuti)"
OK=no
for _ in $(seq 1 24); do
    if curl -fsS "https://$VM_DOMAIN/api/health" >/dev/null 2>&1; then OK=yes; break; fi
    sleep 5
done

echo
if [ "$OK" = yes ]; then
    echo "Server attivo. Inserisci questi dati nell'app, tasto Server:"
else
    echo "Il server e' avviato ma https://$VM_DOMAIN/api/health non risponde ancora."
    echo "Controlla che le porte 80 e 443 siano aperte nel firewall di Lightsail, poi riprova."
    echo "Dati da inserire nell'app quando risponde:"
fi
echo "  Indirizzo: https://$VM_DOMAIN"
echo "  Token:     $VM_API_TOKEN"
"""


def main() -> None:
    root = Path(__file__).parent
    buffer = io.BytesIO()
    with tarfile.open(fileobj=buffer, mode="w:gz") as tar:
        for name in FILES:
            tar.add(root / name, arcname=name)
    payload = base64.encodebytes(buffer.getvalue()).decode().strip()
    print(SCRIPT.replace("@PAYLOAD@", payload), end="")


if __name__ == "__main__":
    main()
