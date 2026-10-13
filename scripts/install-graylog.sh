#!/usr/bin/env bash
set -Eeuo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
GRAYLOG_VERSION="${GRAYLOG_VERSION:-7.1}"
MONGO_URI="${GRAYLOG_MONGO_URI:-mongodb://127.0.0.1:27017}"
MONGO_ADMIN_USER="${MONGO_ADMIN_USER:-admin}"
GRAYLOG_DB="${GRAYLOG_DB:-graylog}"
GRAYLOG_DB_USER="${GRAYLOG_DB_USER:-graylog}"
read -rsp "Senha do administrador MongoDB [$MONGO_ADMIN_USER]: " MONGO_ADMIN_PASSWORD;echo
read -rsp "Senha do usuário MongoDB [$GRAYLOG_DB_USER]: " GRAYLOG_DB_PASSWORD;echo
read -rsp "Senha inicial do administrador Graylog [admin]: " GRAYLOG_ADMIN_PASSWORD;echo
command -v mongosh >/dev/null||{ echo "[ERRO] mongosh ausente.";exit 1; }
mongosh "$MONGO_URI" -u "$MONGO_ADMIN_USER" --authenticationDatabase admin --password "$MONGO_ADMIN_PASSWORD" --quiet --eval 'db.getSiblingDB("'"$GRAYLOG_DB"'").createUser({user:"'"$GRAYLOG_DB_USER"'",pwd:"'"$GRAYLOG_DB_PASSWORD"'",roles:[{role:"readWrite",db:"'"$GRAYLOG_DB"'"},{role:"dbAdmin",db:"'"$GRAYLOG_DB"'"},{role:"clusterMonitor",db:"admin"}]})' 2>/dev/null || mongosh "$MONGO_URI" -u "$MONGO_ADMIN_USER" --authenticationDatabase admin --password "$MONGO_ADMIN_PASSWORD" --quiet --eval 'db.getSiblingDB("'"$GRAYLOG_DB"'").grantRolesToUser("'"$GRAYLOG_DB_USER"'",[{role:"readWrite",db:"'"$GRAYLOG_DB"'"},{role:"dbAdmin",db:"'"$GRAYLOG_DB"'"},{role:"clusterMonitor",db:"admin"}])'
MVER="$(mongosh "$MONGO_URI" -u "$MONGO_ADMIN_USER" --authenticationDatabase admin --password "$MONGO_ADMIN_PASSWORD" --quiet --eval 'db.version()'|tail -1|tr -d '\r')"
[[ "$MVER" =~ ^(7\.|8\.) ]]||{ echo "[ERRO] MongoDB $MVER não é compatível com Graylog 7.1.";exit 2; }
if command -v apt-get >/dev/null;then
 wget -q "https://packages.graylog2.org/repo/packages/graylog-$GRAYLOG_VERSION-repository_latest.deb" -O /tmp/graylog-repo.deb
 dpkg -i /tmp/graylog-repo.deb;apt-get update;apt-get install -y graylog-datanode graylog-server
elif command -v dnf >/dev/null||command -v yum >/dev/null;then
 rpm -Uvh "https://packages.graylog2.org/repo/packages/graylog-$GRAYLOG_VERSION-repository_latest.rpm"||true
 (dnf install -y graylog-datanode graylog-server 2>/dev/null||yum install -y graylog-datanode graylog-server)
else echo "[ERRO] Distribuição não suportada.";exit 3;fi
SECRET="$(openssl rand -hex 48)"
ROOT_HASH="$(printf '%s' "$GRAYLOG_ADMIN_PASSWORD"|sha256sum|awk '{print $1}')"
CFG=/etc/graylog/server/server.conf
DN=/etc/graylog/datanode/datanode.conf
# MinIO ja' ocupa 9000 (S3) e 9001 (console) neste host. Graylog com a porta
# ocupada nao derruba o boot, ele simplesmente nao sobe -- e o sintoma ("Graylog
# fora") nao tem nada a ver com a causa (porta tomada). Auto-deteccao antes de
# escrever: testa de verdade cada candidata em vez de assumir que 9001 esta livre.
HTTP_PORT="${GRAYLOG_HTTP_PORT:-}"
if [[ -z "$HTTP_PORT" ]];then
  HTTP_PORT=""
  for CAND in 9000 9001 9002 9003 9004 9005;do
    if ! ss -lnt 2>/dev/null | grep -qE ":$CAND ";then
      HTTP_PORT="$CAND";break
    fi
    echo "[INFO] :$CAND ocupada; testando a proxima."
  done
  [[ -n "$HTTP_PORT" ]]||{ echo "[ERRO] nenhuma porta livre em 9000-9005; passe GRAYLOG_HTTP_PORT.";exit 4; }
  echo "[INFO] UI do Graylog na porta $HTTP_PORT."
fi
# 0.0.0.0 porque a UI e' administrada da rede local (o mesmo vale para 15672,
# 8181 e 9000 neste host); o que limita o alcance e' o firewalld, nao o bind.
HTTP_BIND="${GRAYLOG_HTTP_BIND:-0.0.0.0}"
HOST_IP="$(hostname -I 2>/dev/null|awk '{print $1}')"
[[ -n "$HOST_IP" ]]||HOST_IP="127.0.0.1"

# O URI precisa da credencial: sem ela o Mongo (authorization: enabled) devolve
# error 13 "Unauthorized" no createIndexes e o datanode morre em loop de
# restart -- e a mensagem de erro fica no arquivo de log, nao no journal.
if [[ "$MONGO_URI" == *"@"* ]];then
  MONGO_URI_AUTH="$MONGO_URI"
else
  SENHA_URL="$(printf '%s' "$GRAYLOG_DB_PASSWORD"|sed -e 's/%/%25/g' -e 's/@/%40/g' -e 's/:/%3A/g' -e 's#/#%2F#g')"
  MONGO_URI_AUTH="$(printf '%s' "$MONGO_URI"|sed -e "s#^\(mongodb://\)#\1${GRAYLOG_DB_USER}:${SENHA_URL}@#")"
fi

# O pacote cria os servicos como 'graylog' e 'graylog-datanode'. Diretorio
# root:root 0750 = o servico nao atravessa e' o Graylog anuncia "server.conf
# does not exist" -- permissao disfarcada de arquivo ausente.
install -d -m 0755 /etc/graylog
install -d -o root -g graylog -m 0750 /etc/graylog/server
install -d -o root -g graylog-datanode -m 0750 /etc/graylog/datanode
touch "$CFG" "$DN"
chown root:graylog "$CFG";chmod 0640 "$CFG"
chown root:graylog-datanode "$DN";chmod 0640 "$DN"
sed -i '/^mongodb_uri[[:space:]]*=/d;/^password_secret[[:space:]]*=/d;/^root_password_sha2[[:space:]]*=/d;/^http_bind_address[[:space:]]*=/d;/^http_external_uri[[:space:]]*=/d;/^http_publish_uri[[:space:]]*=/d;/^selfsigned_startup[[:space:]]*=/d' "$CFG"
printf 'mongodb_uri = %s/%s?authSource=%s\npassword_secret = %s\nroot_username = admin\nroot_password_sha2 = %s\nhttp_bind_address = %s:%s\nhttp_external_uri = http://%s:%s/\nhttp_publish_uri = http://%s:%s/\nselfsigned_startup = true\n' "$MONGO_URI_AUTH" "$GRAYLOG_DB" "$GRAYLOG_DB" "$SECRET" "$ROOT_HASH" "$HTTP_BIND" "$HTTP_PORT" "$HOST_IP" "$HTTP_PORT" "$HOST_IP" "$HTTP_PORT" >> "$CFG"
sed -i '/^mongodb_uri[[:space:]]*=/d;/^password_secret[[:space:]]*=/d;/^root_password_sha2[[:space:]]*=/d' "$DN"
printf 'mongodb_uri = %s/%s?authSource=%s\npassword_secret = %s\nroot_password_sha2 = %s\n' "$MONGO_URI_AUTH" "$GRAYLOG_DB" "$GRAYLOG_DB" "$SECRET" "$ROOT_HASH" >> "$DN"
chown root:graylog "$CFG";chmod 0640 "$CFG"
chown root:graylog-datanode "$DN";chmod 0640 "$DN"

# selfsigned_startup=true: o servidor gera a CA e os datanodes recebem o
# certificado sozinhos. Sem isso, a instalacao para no assistente de preflight
# e' fica esperando clique humano numa caixa que ninguem abriu.
# Porta no firewalld: bind 0.0.0.0 nao adianta se o pacote e' descartado.
if command -v firewall-cmd >/dev/null 2>&1 && firewall-cmd --state >/dev/null 2>&1;then
  firewall-cmd --permanent --add-port="$HTTP_PORT/tcp" >/dev/null 2>&1&&firewall-cmd --reload >/dev/null 2>&1
  echo "[INFO] firewalld: porta $HTTP_PORT/tcp liberada."
fi
systemctl daemon-reload
# A linha abaixo tinha um '\n' literal dentro das aspas duplas: o systemctl
# tentava habilitar uma unidade chamada "graylog-server.service\nGRAYLOG_..."
# e o script morria ali, deixando o Graylog instalado e nunca iniciado.
systemctl enable --now graylog-datanode.service graylog-server.service
echo "[INFO] aguardando a API do Graylog em 127.0.0.1:$HTTP_PORT ..."
GRAYLOG_URL="http://127.0.0.1:$HTTP_PORT" GRAYLOG_ADMIN_PASSWORD="$GRAYLOG_ADMIN_PASSWORD" \
  "$SCRIPT_DIR/configure-graylog-ilm.sh"
echo "[OK] Graylog usa o database Mongo '$GRAYLOG_DB'; collections são criadas pelo Graylog."
echo "     API: http://$HOST_IP:$HTTP_PORT  (admin / a senha que voce digitou)"
