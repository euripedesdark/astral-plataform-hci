#!/usr/bin/env bash
# Funcoes compartilhadas pelos instaladores. Nunca imprimem URI/credenciais.
observability_lock() {
    local lock_dir="${OBSERVABILITY_LOCK_DIR:-/run/lock}"
    command -v flock >/dev/null || { echo '[ERRO] flock ausente.' >&2; return 1; }
    exec 9>"$lock_dir/brasilcloud-observability.lock"
    flock -n 9 || { echo '[ERRO] Outro instalador de observabilidade esta ativo.' >&2; return 1; }
}

reuse_observability_service() {
    local kind="$1" unit="$2" count state
    case "$kind" in
        mongo) count=$(pgrep -x mongod | wc -l) ;;
        graylog) count=$(pgrep -f 'org[.]graylog2[.]bootstrap[.]Main|[/]graylog[^ ]*[.]jar' | wc -l) ;;
        *) echo '[ERRO] Servico desconhecido.' >&2; return 2 ;;
    esac
    state=$(systemctl show "$unit" -p LoadState --value 2>/dev/null || true)
    if (( count > 1 )); then
        echo "[ERRO] $kind possui $count processos. Identifique units/containers/dbPath antes de consolidar." >&2
        return 2
    fi
    if (( count == 1 )); then
        if [[ "$state" == loaded ]] && systemctl is-active --quiet "$unit"; then
            echo "[INFO] Reutilizando $unit; configuracao e credenciais preservadas."
            return 0
        fi
        echo "[ERRO] $kind ja executa fora de $unit. Nenhuma segunda instancia sera iniciada." >&2
        return 2
    fi
    if [[ "$state" == loaded ]]; then
        echo "[INFO] Instalacao existente: iniciando somente $unit."
        systemctl start "$unit" || return 2
        systemctl is-active --quiet "$unit" || return 2
        return 0
    fi
    # 1 significa ausente; 2 significa conflito/falha e deve interromper.
    return 1
}
