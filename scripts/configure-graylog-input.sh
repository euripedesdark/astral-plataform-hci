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
# A porta da UI nao e' fixa (MinIO toma 9000 e 9001 neste host). Sem GRAYLOG_URL,
# le a porta do server.conf em vez de adivinhar -- adivinhar e' como falar com o
# MinIO achando que e' o Graylog: a chamada "da certo" e' o erro passa batido.
URL="${GRAYLOG_URL:-}"
if [[ -z "$URL" ]];then
  CONF="${GRAYLOG_CONF:-/etc/graylog/server/server.conf}"
  PORT_UI=""
  if [[ -r "$CONF" ]];then
    PORT_UI="$( (sed -n 's/^http_bind_address[[:space:]]*=[[:space:]]*//p' "$CONF" || true) | head -1 | awk -F: '{print $NF}')"
  fi
  if [[ -n "$PORT_UI" ]];then
    URL="http://127.0.0.1:$PORT_UI"
  else
    echo "[ERRO] nao li a porta em $CONF (existe? legivel por este usuario?)." >&2
    echo "       rode com sudo ou passe GRAYLOG_URL=http://127.0.0.1:<porta>." >&2
    exit 1
  fi
fi
export GRAYLOG_URL="$URL"
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
