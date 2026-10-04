#!/usr/bin/env bash
# ILM do Graylog: input GELF + rotacao + retencao de 30 dias.
#
# POR QUE EXISTE: sem retencao, o Graylog enche o disco e o dia em que ele
# enche e' o dia em que a auditoria para -- o sistema cai por falta de log,
# que e' o pior jeito de cair. 30 dias e' o mesmo horizonte do TimescaleDB
# (scripts/configure-timescaledb.sh): mesma pergunta, mesma resposta nos dois
# lados da observabilidade.
#
# COMO DECIDE (e o que NAO decide):
#   - retencao: coloca 'delete' de N dias. Essa e' a garantia, e ela e'
#     explicita neste script porque e' o requisito de retencao.
#   - rotacao: ajusta os LIMITES JA' EXISTENTES da strategy atual. Este script
#     nao troca a strategy de lugar: cada versao do Graylog tem um formato de
#     JSON proprio (count/time/size/index_time_size_optimizing) e trocar no
#     escuro e' como descobrir o formato errado em producao. Se os limites nao
#     existirem no JSON, ele mostra o que recebeu e manda o operador para a
#     tela System -> Indices -- com o motivo escrito.
#
# IDEMPOTENTE: rodar duas vezes nao muda nada alem de reaplicar os mesmos
# valores. A GELF UDP e' delegada para configure-graylog-input.sh, que tambem
# e' idempotente.
#
# Uso:
#   GRAYLOG_ADMIN_PASSWORD=senha scripts/configure-graylog-ilm.sh
#   GRAYLOG_URL=http://127.0.0.1:9001 DIAS=60 scripts/configure-graylog-ilm.sh
set -Eeuo pipefail

URL="${GRAYLOG_URL:-http://127.0.0.1:9000}"
DIAS="${ASTRAL_GRAYLOG_RETENTION_DAYS:-30}"
USUARIO="${GRAYLOG_ADMIN_USER:-admin}"
SENHA="${GRAYLOG_ADMIN_PASSWORD:-}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
XRH='X-Requested-By: astral-astral'
AUTH=(-u "$USUARIO:$SENHA")

[[ -n "$SENHA" ]] || { echo "[ERRO] GRAYLOG_ADMIN_PASSWORD nao definido."; exit 1; }
command -v curl >/dev/null || { echo "[ERRO] curl ausente."; exit 1; }
command -v python3 >/dev/null || { echo "[ERRO] python3 ausente."; exit 1; }

api() { curl -sS -H "$XRH" -H 'Content-Type: application/json' "${AUTH[@]}" "$@"; }

# --- 0) API de pé ---------------------------------------------------------------
echo "[INFO] aguardando a API do Graylog em $URL ..."
for i in $(seq 1 60); do
  curl -fsS "${AUTH[@]}" "$URL/api/system/cluster/nodes" >/dev/null 2>&1 && break
  sleep 2
  if [[ $i -eq 60 ]]; then
    echo "[ERRO] API do Graylog nao respondeu em 120s."
    echo "       confira systemctl status graylog-server e a porta em"
    echo "       http_bind_address do /etc/graylog/server/server.conf"
    echo "       (MinIO ja' usa 127.0.0.1:9000 neste host: troque para 9001)."
    exit 1
  fi
done
echo "[OK] API do Graylog respondendo."

# --- 1) input GELF --------------------------------------------------------------
bash "$SCRIPT_DIR/configure-graylog-input.sh"

# --- 2) index set padrao --------------------------------------------------------
LISTA="$(api "$URL/api/system/indices/indexsets" || true)"
[[ -n "$LISTA" ]] || { echo "[ERRO] GET /api/system/indices/indexsets falhou."; exit 1; }

ID="$(printf '%s' "$LISTA" | python3 -c '
import json,sys
d=json.load(sys.stdin)
alvos=d.get("index_sets") or d.get("indexsets") or d
for i in alvos:
    if i.get("is_default"):
        print(i["id"]); break
else:
    if alvos: print(alvos[0]["id"])
')"
[[ -n "$ID" ]] || { echo "[ERRO] nenhum index set encontrado."; exit 1; }
echo "[INFO] index set: $ID"

ATUAL="$(api "$URL/api/system/indices/indexsets/$ID")"
printf '%s' "$ATUAL" | python3 -c '
import json,sys
d=json.load(sys.stdin)
print("      rotacao atual :", json.dumps(d.get("rotation_strategy"), ensure_ascii=False))
print("      retencao atual:", json.dumps(d.get("retention_strategy"), ensure_ascii=False))
'

# --- 3) aplica retencao (garantia) e ajusta a rotacao (limites ja' existentes) ---
NOVO="$(printf '%s' "$ATUAL" | DIAS="$DIAS" python3 -c '
import json,sys,os
dias=int(os.environ["DIAS"])
ms=dias*24*60*60*1000
d=json.load(sys.stdin)

# A garantia: apaga mensagem com mais de N dias.
d["retention_strategy"]={"@type":"delete","@value":{"max_period":ms}}

# A rotacao: so' mexe em limites que ja' existem na strategy atual.
r=d.get("rotation_strategy") or {}
tipo=r.get("@type","")
val=r.get("@value") or {}
mudou=[]
if "max_period" in val:   # time-based: janela por indice
    val["max_period"]=min(val["max_period"], ms); mudou.append("max_period")
if "max_indices" in val:  # count-based: quantos indices ficam vivos
    val["max_indices"]=max(int(val["max_indices"]), 1); mudou.append("max_indices")
if "max_size" in val and tipo=="size":
    mudou.append("max_size (mantido)")
if mudou:
    d["rotation_strategy"]=r
    sys.stderr.write("      rotacao ajustada: "+", ".join(mudou)+"\n")
else:
    sys.stderr.write("      rotacao do tipo %r sem limite ajustavel neste JSON;\n" % tipo)
    sys.stderr.write("      revise System -> Indices se quiser trocar a strategy.\n")
print(json.dumps(d))
')"

RESP="$(api -o /tmp/astral-graylog-ilm.out -w '%{http_code}' -X PUT \
  "$URL/api/system/indices/indexsets/$ID" --data "$NOVO")"
CODIGO="$RESP"
if [[ "$CODIGO" != 200 && "$CODIGO" != 204 ]]; then
  echo "[ERRO] PUT devolveu $CODIGO. Resposta do servidor:"
  cat /tmp/astral-graylog-ilm.out 2>/dev/null || true
  echo
  echo "       O que foi enviado (retencao de ${DIAS} dias):"
  printf '%s' "$NOVO" | python3 -m json.tool | sed -n '/retention_strategy/,+4p'
  exit 1
fi
rm -f /tmp/astral-graylog-ilm.out
echo "[OK] retencao de ${DIAS} dias aplicada ao index set $ID."

# --- 4) prova de vida -----------------------------------------------------------
FINAL="$(api "$URL/api/system/indices/indexsets/$ID")"
printf '%s' "$FINAL" | DIAS="$DIAS" python3 -c '
import json,sys,os
d=json.load(sys.stdin)
di=int(os.environ.get("DIAS","30"))
rs=d.get("retention_strategy") or {}
val=rs.get("@value") or {}
periodo=val.get("max_period")
print("      retencao final:", json.dumps(rs, ensure_ascii=False))
if periodo is not None:
    dias=periodo/(24*60*60*1000)
    print("      = %.1f dias  (%s)" % (dias, "OK" if abs(dias-di)<0.01 else "DIVERGENTE"))
' 2>/dev/null || true

echo "[OK] Graylog ILM: input GELF + rotacao + retencao de ${DIAS} dias."
