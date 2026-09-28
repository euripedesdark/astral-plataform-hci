#!/usr/bin/env bash
set -Eeuo pipefail
[[ $EUID -eq 0 ]]||{ echo "Execute com sudo.";exit 1; }
GRAYLOG_INPUT_PORT="${GRAYLOG_INPUT_PORT:-12201}"
if command -v firewall-cmd >/dev/null && firewall-cmd --state >/dev/null 2>&1; then
 firewall-cmd --permanent --add-port=8080/tcp >/dev/null
 firewall-cmd --permanent --add-port="$GRAYLOG_INPUT_PORT/udp" >/dev/null
 firewall-cmd --reload >/dev/null
 echo "[OK] firewalld: 8080/tcp e $GRAYLOG_INPUT_PORT/udp adicionadas sem remover regras existentes."
else
 iptables -C INPUT -p tcp --dport 8080 -j ACCEPT 2>/dev/null||iptables -I INPUT 1 -p tcp --dport 8080 -j ACCEPT
 iptables -C INPUT -p udp --dport "$GRAYLOG_INPUT_PORT" -j ACCEPT 2>/dev/null||iptables -I INPUT 1 -p udp --dport "$GRAYLOG_INPUT_PORT" -j ACCEPT
 if command -v netfilter-persistent >/dev/null;then netfilter-persistent save >/dev/null 2>&1||true
 elif command -v iptables-save >/dev/null;then mkdir -p /etc/iptables;iptables-save >/etc/iptables/rules.v4.tmp && mv /etc/iptables/rules.v4.tmp /etc/iptables/rules.v4
 fi
 echo "[OK] iptables: 8080/tcp e $GRAYLOG_INPUT_PORT/udp adicionadas sem flush."
fi
