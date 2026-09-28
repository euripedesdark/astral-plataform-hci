#!/usr/bin/env bash
set -Eeuo pipefail
CFG="$ATS_CONFIG_DIR"
: "${CFG:=}"
if [[ -z "$CFG" ]]; then [[ -d /etc/trafficserver ]]&&CFG=/etc/trafficserver||CFG=/opt/trafficserver/etc/trafficserver; fi
[[ -d "$CFG" ]]||{ echo "[ERRO] ATS não encontrado"; exit 1; }
touch "$CFG/plugin.config"
cp -a "$CFG/plugin.config" "$CFG/plugin.config.astral.bak.$(date +%s)" 2>/dev/null || true
grep -q 'authproxy.so' "$CFG/plugin.config" || echo 'authproxy.so --auth-transform=redirect --auth-host=127.0.0.1 --auth-port=8091' >> "$CFG/plugin.config"
if [[ -f "$CFG/records.yaml" ]]; then
 grep -q 'doc_in_cache_skip_dns' "$CFG/records.yaml" || cat >> "$CFG/records.yaml" <<'EOF'
http:
  doc_in_cache_skip_dns: 0
  cache:
    ignore_authentication: 0
EOF
fi
if command -v traffic_ctl >/dev/null; then traffic_ctl config reload 2>/dev/null || systemctl restart trafficserver; else /opt/trafficserver/bin/traffic_ctl config reload 2>/dev/null || systemctl restart trafficserver; fi
echo "[OK] ATS -> AuthProxy -> Astral AD/PostgreSQL."
