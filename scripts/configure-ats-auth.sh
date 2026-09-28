#!/usr/bin/env bash
set -Eeuo pipefail
CFG="${ATS_CONFIG_DIR:-}"
if [[ -z "$CFG" ]]; then [[ -d /etc/trafficserver ]]&&CFG=/etc/trafficserver||CFG=/opt/trafficserver/etc/trafficserver; fi
[[ -d "$CFG" ]]||{ echo "[ERRO] ATS não encontrado"; exit 1; }
if [[ -f "$CFG/plugin.yaml" ]]; then
 cp -a "$CFG/plugin.yaml" "$CFG/plugin.yaml.astral.bak.$(date +%s)"
 grep -q 'authproxy.so' "$CFG/plugin.yaml" || cat >> "$CFG/plugin.yaml" <<'EOF'

  - path: authproxy.so
    params:
      - --auth-transform=redirect
      - --auth-host=127.0.0.1
      - --auth-port=8091
EOF
else
 cp -a "$CFG/plugin.config" "$CFG/plugin.config.astral.bak.$(date +%s)" 2>/dev/null || true
 touch "$CFG/plugin.config"
 grep -q 'authproxy.so' "$CFG/plugin.config" || echo 'authproxy.so --auth-transform=redirect --auth-host=127.0.0.1 --auth-port=8091' >> "$CFG/plugin.config"
fi
if [[ -f "$CFG/records.yaml" ]] && ! grep -q 'doc_in_cache_skip_dns:' "$CFG/records.yaml"; then
 if grep -q '^http:' "$CFG/records.yaml"; then
  sed -i '/^http:/a\  doc_in_cache_skip_dns: 0' "$CFG/records.yaml"
 else
  cat >> "$CFG/records.yaml" <<'EOF'

http:
  doc_in_cache_skip_dns: 0
EOF
 fi
fi
if command -v traffic_ctl >/dev/null; then traffic_ctl config reload 2>/dev/null || systemctl restart trafficserver; else /opt/trafficserver/bin/traffic_ctl config reload 2>/dev/null || systemctl restart trafficserver; fi
echo "[OK] ATS -> AuthProxy -> Astral AD/PostgreSQL."
