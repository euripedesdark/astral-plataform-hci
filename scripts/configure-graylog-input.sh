#!/usr/bin/env bash
# Input GELF UDP do Graylog -- onde a auditoria do Astral chega.
#
# Idempotente: se o input "Astral GELF UDP" ja' existe, devolve 0 e nao cria
# outro. Criar input duplicado e' como ter dois canais com o mesmo nome: a
# auditoria passa a aparecer duas vezes ou nao aparecer, dependendo de qual
# dos dois o Graylog pergunta.
#
# A porta 12201 e' a que o GraylogAuditPublisher do Astral envia por padrao
# (astral.graylog.gelf-port). Se trocar de um lado, troque do outro.
set -Eeuo pipefail
PORT="${GRAYLOG_GELF_PORT:-12201}"
URL="${GRAYLOG_URL:-http://127.0.0.1:9000}"
ADMIN_USER="${GRAYLOG_ADMIN_USER:-admin}"
ADMIN_PASSWORD="${GRAYLOG_ADMIN_PASSWORD:-}"
if [[ -z "$ADMIN_PASSWORD" ]];then read -rsp "Senha do administrador Graylog [admin]: " ADMIN_PASSWORD;echo;fi
AUTH=(-u "$ADMIN_USER:$ADMIN_PASSWORD")
XRH='X-Requested-By: astral-installer'

for i in $(seq 1 30);do curl -fsS "${AUTH[@]}" "$URL/api/system/cluster/nodes" >/dev/null 2>&1&&break;sleep 2;done

EXISTING="$(curl -fsS "${AUTH[@]}" -H "$XRH" "$URL/api/system/inputs" 2>/dev/null||true)"
if printf '%s' "$EXISTING"|grep -q 'Astral GELF UDP';then echo "[OK] Input GELF UDP já existe.";exit 0;fi

curl -fsS "${AUTH[@]}" -H "$XRH" -H 'Content-Type: application/json' -X POST "$URL/api/system/inputs" \
  --data "{\"title\":\"Astral GELF UDP\",\"type\":\"org.graylog2.inputs.gelf.udp.GELFUDPInput\",\"global\":true,\"configuration\":{\"bind_address\":\"0.0.0.0\",\"port\":$PORT,\"recv_buffer_size\":262144,\"number_worker_threads\":2,\"decompress_size_limit\":8388608}}" >/dev/null
echo "[OK] Graylog GELF UDP ativo em 0.0.0.0:$PORT."
