#!/usr/bin/env bash
set -u
check(){ "$@" >/dev/null 2>&1&&echo "[OK] $*"||echo "[FAIL] $*"; }
check systemctl is-active astral-platform
check curl -fsS http://127.0.0.1:8081/actuator/health
check curl -fsS http://127.0.0.1:8080/app/
command -v traffic_ctl >/dev/null&&check systemctl is-active trafficserver
systemctl list-unit-files graylog-server.service >/dev/null 2>&1&&check systemctl is-active graylog-server
