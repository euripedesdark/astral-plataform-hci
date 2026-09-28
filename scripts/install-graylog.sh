#!/usr/bin/env bash
set -Eeuo pipefail
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
install -d -m 0750 /etc/graylog/server;touch "$CFG"
sed -i '/^mongodb_uri[[:space:]]*=/d;/^password_secret[[:space:]]*=/d;/^root_password_sha2[[:space:]]*=/d;/^http_bind_address[[:space:]]*=/d;/^http_external_uri[[:space:]]*=/d' "$CFG"
printf 'mongodb_uri = %s/%s?authSource=%s\npassword_secret = %s\nroot_username = admin\nroot_password_sha2 = %s\nhttp_bind_address = 127.0.0.1:9000\nhttp_external_uri = http://127.0.0.1:9000/\n' "$MONGO_URI" "$GRAYLOG_DB" "$GRAYLOG_DB" "$SECRET" "$ROOT_HASH" >> "$CFG"
DN=/etc/graylog/datanode/datanode.conf
install -d -m 0750 /etc/graylog/datanode;touch "$DN"
sed -i '/^mongodb_uri[[:space:]]*=/d;/^password_secret[[:space:]]*=/d;/^root_password_sha2[[:space:]]*=/d' "$DN"
printf 'mongodb_uri = %s/%s?authSource=%s\npassword_secret = %s\nroot_password_sha2 = %s\n' "$MONGO_URI" "$GRAYLOG_DB" "$GRAYLOG_DB" "$SECRET" "$ROOT_HASH" >> "$DN"
systemctl daemon-reload
systemctl enable --now graylog-datanode.service graylog-server.service\nGRAYLOG_ADMIN_PASSWORD="$GRAYLOG_ADMIN_PASSWORD" "$PWD/scripts/configure-graylog-input.sh"
echo "[OK] Graylog usa o database Mongo '$GRAYLOG_DB'; collections são criadas pelo Graylog."
