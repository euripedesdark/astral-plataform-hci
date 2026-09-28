#!/usr/bin/env bash
set -Eeuo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
[[ $EUID -eq 0 ]]||{ echo "Execute com sudo.";exit 1; }
command -v java >/dev/null||{ echo "[ERRO] Java ausente.";exit 1; }
command -v mvn >/dev/null||{ echo "[ERRO] Maven ausente.";exit 1; }

# --- preflight: o banco precisa existir ANTES de enable --now ---------------------
# Antes nao havia checagem nenhuma. O script fazia "touch /etc/astral/astral.env"
# (arquivo VAZIO) e subia a unit, que com ddl-auto=update caia em
# "Unable to determine Dialect without JDBC metadata" e entrava em crash-loop de
# 5 em 5 segundos porque o Restart=on-failure. Descobrir isso no journal e horrivel;
# falhar aqui, com a instrucao do que fazer, e o esperado.
DB_NAME="${ASTRAL_DB_NAME:-astral}"
DB_USER="${ASTRAL_DB_USER:-astral}"
psql_super(){ PGPASSWORD="${PGPASSWORD:-}" psql -h 127.0.0.1 -U postgres -d postgres -tAc "$1" 2>/dev/null; }
if [[ -z "$(psql_super "select 1 from pg_database where datname='$DB_NAME'")" ]];then
  echo "[ERRO] Banco '$DB_NAME' nao existe no Postgres local."
  echo "[ERRO] Crie antes de instalar:"
  echo "         sudo -u postgres createdb $DB_NAME"
  echo "         sudo -u postgres psql -c \"create role $DB_USER login password '...'\""
  echo "[ERRO] Alternativa: export ASTRAL_DB_NAME=<outro_banco> e rode de novo."
  exit 1
fi
# --- usuario de servico dedicated -------------------------------------------------
id -u astral >/dev/null 2>&1||useradd --system --home-dir /opt/astral-platform --shell /usr/sbin/nologin astral
install -d -m 0750 -o root -g astral /etc/astral
install -d -m 0755 -o root -g astral /opt/astral-platform
[[ -e /etc/astral/ad.properties ]]&&cp -a /etc/astral/ad.properties "/etc/astral/ad.properties.astral.bak.$(date +%s)"||true
[[ -e /etc/trafficserver/plugin.config ]]&&cp -a /etc/trafficserver/plugin.config "/etc/trafficserver/plugin.config.astral.bak.$(date +%s)"||true
cd "$ROOT"
if command -v npm >/dev/null && [[ -f src/main/resources/static/react/package.json ]]; then
  if [[ ! -f src/main/resources/static/react/index.html ]]; then
    echo "[ERRO] Frontend Vite sem index.html em src/main/resources/static/react."
    echo "[ERRO] O build foi interrompido para não gerar um artefato inválido."
    exit 1
  fi
  (cd src/main/resources/static/react && npm install && npm run build)
fi
mvn -DskipTests clean package
# O build roda como root (o script precisa de root), mas o repo e do usuario. Sem isto o
# target/ fica com ownership de root e o PROXIMO build do usuario falha com
# "Failed to delete target/...jar". Ja aconteceu: o primeiro deploy deixou o repo travado.
REPO_OWNER="$(stat -c '%U:%G' "$ROOT")"
chown -R "$REPO_OWNER" "$ROOT/target" 2>/dev/null||true
JAR="$(find target -maxdepth 1 -type f -name '*.jar' ! -name '*sources*'|head -1)"
[[ -n "$JAR" ]]||{ echo "[ERRO] JAR não gerado.";exit 1; }
install -m 0640 -o root -g astral "$JAR" /opt/astral-platform/astral-platform.jar

# --- env: popula o que falta, sem sobrescrever o que o usuario ja configurou -------
ENVF=/etc/astral/astral.env
touch "$ENVF";chmod 0640 "$ENVF";chown root:astral "$ENVF"
have(){ grep -qE "^[[:space:]]*$1=" "$ENVF"; }
ensure(){ local k=$1 v=$2; have "$k" || printf '%s=%s\n' "$k" "$v" >>"$ENVF"; }
ensure ASTRAL_DB_URL       "jdbc:postgresql://127.0.0.1:5432/$DB_NAME"
ensure ASTRAL_DB_USER      "$DB_USER"
ensure ASTRAL_DB_PASSWORD  "${ASTRAL_DB_PASSWORD:-}"
ensure ASTRAL_AUTH_POSTGRES_URL "jdbc:postgresql://127.0.0.1:5432/$DB_NAME"
[[ -s "$ENVF" ]] || { echo "[ERRO] $ENVF ficou vazio: sem ASTRAL_DB_PASSWORD a app sobe apontando pra credencial vazia."; exit 1; }

cat >/etc/systemd/system/astral-platform.service <<'EOF'
[Unit]
Description=Astral Platform & HCI
After=network-online.target postgresql.service
Wants=network-online.target
[Service]
Type=simple
# Antes era root. A app faz proxy HTTP para o firewall, fala LDAP e Postgres:
# nada disso precisa de root.
User=astral
Group=astral
WorkingDirectory=/opt/astral-platform
EnvironmentFile=/etc/astral/astral.env
ExecStart=/usr/bin/java -jar /opt/astral-platform/astral-platform.jar
Restart=on-failure
RestartSec=5
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
StandardOutput=journal
StandardError=journal
[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
# enable --now NAO reinicia uma unit que ja esta active: o processo continua com o JAR
# antigo em memoria e o deploy "passa" sem trocar nada. Tem que ser enable + restart.
systemctl enable astral-platform.service
systemctl restart astral-platform.service
"$ROOT/scripts/configure-system-firewall.sh"
if command -v traffic_ctl >/dev/null || [[ -x /opt/trafficserver/bin/traffic_ctl ]];then "$ROOT/scripts/configure-ats-auth.sh";fi
echo "[OK] Deploy concluído sem apagar firewall, PostgreSQL, Samba AD ou ATS."
