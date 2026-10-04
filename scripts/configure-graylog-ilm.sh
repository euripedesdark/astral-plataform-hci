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
#   GRAYLOG_URL=http://127.0.0.1:9002 DIAS=60 scripts/configure-graylog-ilm.sh
set -Eeuo pipefail

URL="${GRAYLOG_URL:-http://127.0.0.1:9000}"
# Mesma logica do configure-graylog-input.sh: porta da UI nao e' fixa neste host
# (MinIO toma 9000/9001), entao sem GRAYLOG_URL explicito a fonte e' o server.conf.
# server.conf e' 0640 root:graylog -- sem permissao o sed falha e o 'set -e'
# mataria o script sem mensagem nenhuma. Prefiro dizer o que faltou.
if [[ -z "${GRAYLOG_URL:-}" ]];then
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
    echo "       (MinIO ja' usa 127.0.0.1:9000 e 9001 neste host: troque para 9002)."
    exit 1
  fi
done
echo "[OK] API do Graylog respondendo."

# --- 1) input GELF --------------------------------------------------------------
bash "$SCRIPT_DIR/configure-graylog-input.sh"

# --- 2) index set padrao --------------------------------------------------------
# Graylog 7 renomeou a rota (indexsets -> index_sets) e o corpo do JSON muda de
# versao para versao. Em vez de adivinhar, prova as rotas e aceita qualquer
# formato de lista -- uma busca de id errado' e' um PUT no documento errado.
ROTA=""
for CAND in index_sets indexsets; do
  if api "$URL/api/system/indices/$CAND" | grep -q '"id"'; then
    ROTA="$CAND"
    break
  fi
done
[[ -n "$ROTA" ]] || { echo "[ERRO] nenhuma rota de index set respondeu (index_sets/indexsets)."; exit 1; }

LISTA="$(api "$URL/api/system/indices/$ROTA" || true)"
[[ -n "$LISTA" ]] || { echo "[ERRO] GET /api/system/indices/$ROTA falhou."; exit 1; }
echo "[INFO] rota de index sets: /api/system/indices/$ROTA"

ID="$(printf '%s' "$LISTA" | python3 -c '
import json,sys
d=json.load(sys.stdin)
if isinstance(d,list):
    alvos=d
else:
    alvos=None
    for k in ("index_sets","indexsets","indexSets","elements","items"):
        if isinstance(d.get(k),list):
            alvos=d[k]; break
    if alvos is None:
        alvos=[v for v in d.values() if isinstance(v,list) and v and isinstance(v[0],dict)]
        alvos=alvos[0] if alvos else []
alvos=[i for i in alvos if isinstance(i,dict) and i.get("id")]
for i in alvos:
    if i.get("is_default"):
        print(i["id"]); break
else:
    if alvos: print(alvos[0]["id"])
')"
[[ -n "$ID" ]] || { echo "[ERRO] nenhum index set encontrado."; exit 1; }
echo "[INFO] index set: $ID"

ATUAL="$(api "$URL/api/system/indices/$ROTA/$ID")"
printf '%s' "$ATUAL" | python3 -c '
import json,sys
d=json.load(sys.stdin)
print("      rotacao atual :", json.dumps(d.get("rotation_strategy"), ensure_ascii=False))
print("      retencao atual:", json.dumps(d.get("retention_strategy"), ensure_ascii=False))
'

# --- 3) aplica retencao (garantia) e ajusta a rotacao -----------------------------
# Dois formatos de JSON convivem: o antigo (@type/@value, janela de tempo) e o
# do Graylog 6/7 (chaves planas; retencao por QUANTIDADE de indices, nao por
# tempo). No formato novo "30 dias" so' existe como acoplamento: com indices
# que vivem 30 dias, manter 1 indice equivale a ~30 dias de dado. O numero vem
# da vida do indice de proposito -- se a rotacao mudar, quem mudou a rotacao
# ve na tela que a retencao acompanha.
#
# CUIDADO com aspa simples abaixo: um apostrofo crus dentro deste literal fecha
# o aspa simples do bash no meio do programa, o Python recebe um pedaco
# truncado, roda sem erro e sem saida (exit 0), e o PUT sai com corpo vazio.
# Por isso o comentario em portugues aqui nao tem apostrofo, e por isso existe
# a guarda logo em seguida.
NOVO="$(printf '%s' "$ATUAL" | DIAS="$DIAS" python3 -c '
import json,sys,os
dias=int(os.environ["DIAS"])
d=json.load(sys.stdin)
rot=d.get("rotation_strategy") or {}
ret=d.get("retention_strategy") or {}
tier=d.get("data_tiering") or {}
mudou=[]
if "@type" in rot or "@type" in ret:
    ms=dias*24*60*60*1000
    if "@type" in ret:
        v=dict(ret.get("@value") or {})
        v["max_period"]=ms
        d["retention_strategy"]={"@type":ret.get("@type","delete"),"@value":v}
        mudou.append("retencao max_period")
    v=rot.get("@value") or {}
    if "max_period" in v:
        v["max_period"]=min(v["max_period"],ms)
        rot["@value"]=v
        d["rotation_strategy"]=rot
        mudou.append("rotacao max_period")
else:
    # Graylog exige (index_lifetime_max - index_lifetime_min) >= P1D, senao
    # responde 400: com os dois em P30D a diferenca e PT0S. Logo, o aperto
    # legal mais proximo de 30 dias e min=P30D e max=P31D: nada e rotacionado
    # antes de 30 dias (o horizonte exigido e respeitado) e o indice tambem
    # nao fica segurado por 40 dias (o padrao), que e 33% mais disco.
    p="P%dD"%dias
    pmax="P%dD"%(dias+1)
    rot["index_lifetime_min"]=p
    rot["index_lifetime_max"]=pmax
    d["rotation_strategy"]=rot
    mudou.append("rotacao %s/%s"%(p,pmax))
    if str(ret.get("type","")).endswith("DeletionRetentionStrategyConfig"):
        ret["max_number_of_indices"]=1
        d["retention_strategy"]=ret
        mudou.append("retencao 1 indice (vida do indice = %s..%s)"%(p,pmax))
    elif ret:
        mudou.append("retencao %s nao e por tempo; mantida como esta"%str(ret.get("type")))
    if isinstance(tier,dict) and "index_lifetime_min" in tier:
        tier["index_lifetime_min"]=p
        tier["index_lifetime_max"]=pmax
        d["data_tiering"]=tier
        mudou.append("data_tiering %s/%s"%(p,pmax))
sys.stderr.write("      ajustes: "+("; ".join(mudou) if mudou else "nenhum")+"\n")
print(json.dumps(d))
')"

# Guarda: corpo vazio vira 400 "nao deve ser nulo" na API e a mensagem nao aponta
# para a causa. Um PUT com corpo errado e' como reescrever o index set com o
# documento de outro lugar.
[[ -n "$NOVO" ]] || { echo "[ERRO] JSON vazio: o Python nao devolveu nada (veja os ajustes acima)."; exit 1; }
if ! printf '%s' "$NOVO" | python3 -m json.tool >/dev/null 2>&1; then
  echo "[ERRO] JSON invalido montado; nada foi enviado ao Graylog."
  printf '%s' "$NOVO" | head -c 300; echo
  exit 1
fi

RESP="$(api -o /tmp/astral-graylog-ilm.out -w '%{http_code}' -X PUT \
  "$URL/api/system/indices/$ROTA/$ID" --data "$NOVO")"
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
FINAL="$(api "$URL/api/system/indices/$ROTA/$ID")"
printf '%s' "$FINAL" | DIAS="$DIAS" python3 -c '
import json,sys,os
d=json.load(sys.stdin)
di=int(os.environ.get("DIAS","30"))
rot=d.get("rotation_strategy") or {}
ret=d.get("retention_strategy") or {}
print("      rotacao final :", json.dumps(rot, ensure_ascii=False))
print("      retencao final:", json.dumps(ret, ensure_ascii=False))
amin="P%dD"%di
amax="P%dD"%(di+1)
if "index_lifetime_max" in rot:
    ok=rot.get("index_lifetime_min")==amin and rot.get("index_lifetime_max")==amax
    print("      vida do indice = %s..%s  (%s)"%(rot.get("index_lifetime_min"),rot.get("index_lifetime_max"),"OK" if ok else "DIVERGENTE"))
elif "max_period" in (rot.get("@value") or {}):
    v=(rot["@value"]["max_period"])/(24*60*60*1000)
    print("      = %.1f dias  (%s)"%(v,"OK" if abs(v-di)<0.01 else "DIVERGENTE"))
if str(ret.get("type","")).endswith("DeletionRetentionStrategyConfig"):
    print("      retencao por contagem = %s indice(s), com vida do indice %s..%s"%(ret.get("max_number_of_indices"),rot.get("index_lifetime_min"),rot.get("index_lifetime_max")))
elif "@value" in ret:
    v=(ret["@value"].get("max_period") or 0)/(24*60*60*1000)
    print("      retencao = %.1f dias  (%s)"%(v,"OK" if abs(v-di)<0.01 else "DIVERGENTE"))
' 2>/dev/null || true

echo "[OK] Graylog ILM: input GELF + rotacao + retencao de ${DIAS} dias."
