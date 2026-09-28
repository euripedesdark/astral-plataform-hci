#!/usr/bin/env bash
set -Eeuo pipefail
PORT="${GRAYLOG_GELF_PORT:-12201}"
ADMIN_PASSWORD="${GRAYLOG_ADMIN_PASSWORD:-}"
if [[ -z "$ADMIN_PASSWORD" ]];then read -rsp "Senha do administrador Graylog [admin]: " ADMIN_PASSWORD;echo;fi
for i in $(seq 1 30);do curl -fsS http://127.0.0.1:9000/api/system/cluster/nodes >/dev/null 2>&1&&break;sleep 2;done
EXISTING="$(curl -fsS -u "admin:$ADMIN_PASSWORD" -H 'X-Requested-By: astral-installer' http://127.0.0.1:9000/api/system/inputs 2>/dev/null||true)"
if printf '%s' "$EXISTING"|grep -q 'Astral GELF UDP';then echo "[OK] Input GELF UDP já existe.";exit 0;fi
curl -fsS -u "admin:$ADMIN_PASSWORD" -H 'X-Requested-By: astral-installer' -H 'Content-Type: application/json'  -X POST http://127.0.0.1:9000/api/system/inputs  --data "{"title":"Astral GELF UDP","type":"org.graylog2.inputs.gelf.udp.GELFUDPInput","global":true,"configuration":{"bind_address":"0.0.0.0","port":$PORT,"recv_buffer_size":262144,"number_worker_threads":2,"decompress_size_limit":8388608}}" >/dev/null
echo "[OK] Graylog GELF UDP ativo em 0.0.0.0:$PORT."
