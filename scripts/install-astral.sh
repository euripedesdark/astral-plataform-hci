#!/usr/bin/env bash
set -Eeuo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
[[ $EUID -eq 0 ]]||{ echo "Execute com sudo.";exit 1; }
command -v java >/dev/null||{ echo "[ERRO] Java ausente.";exit 1; }
command -v mvn >/dev/null||{ echo "[ERRO] Maven ausente.";exit 1; }
install -d -m 0750 /etc/astral /opt/astral-platform
[[ -e /etc/astral/ad.properties ]]&&cp -a /etc/astral/ad.properties "/etc/astral/ad.properties.astral.bak.$(date +%s)"||true
[[ -e /etc/trafficserver/plugin.config ]]&&cp -a /etc/trafficserver/plugin.config "/etc/trafficserver/plugin.config.astral.bak.$(date +%s)"||true
cd "$ROOT"
if command -v npm >/dev/null && [[ -f src/main/resources/static/react/package.json ]]; then (cd src/main/resources/static/react && npm install && npm run build);fi
mvn -DskipTests clean package
JAR="$(find target -maxdepth 1 -type f -name '*.jar' ! -name '*sources*'|head -1)"
[[ -n "$JAR" ]]||{ echo "[ERRO] JAR não gerado.";exit 1; }
install -m 0644 "$JAR" /opt/astral-platform/astral-platform.jar
touch /etc/astral/astral.env;chmod 0640 /etc/astral/astral.env
cat >/etc/systemd/system/astral-platform.service <<'EOF'
[Unit]
Description=Astral Platform & HCI
After=network-online.target postgresql.service
Wants=network-online.target
[Service]
Type=simple
User=root
WorkingDirectory=/opt/astral-platform
EnvironmentFile=-/etc/astral/astral.env
ExecStart=/usr/bin/java -jar /opt/astral-platform/astral-platform.jar
Restart=on-failure
RestartSec=5
StandardOutput=journal
StandardError=journal
[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable --now astral-platform
if command -v traffic_ctl >/dev/null || [[ -x /opt/trafficserver/bin/traffic_ctl ]];then "$ROOT/scripts/configure-ats-auth.sh";fi
echo "[OK] Deploy concluído sem apagar firewall, PostgreSQL, Samba AD ou ATS."
