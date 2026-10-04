#!/usr/bin/env bash
# TimescaleDB no banco 'astral': extensao, hypertables e retencao de 30 dias.
#
# POR QUE EM SCRIPT E NAO NA MIGRATION (V100__acl_observabilidade_retencao.sql)
#   create_hypertable e add_retention_policy exigem a extensao carregada e
#   permissao de superusuario/dono. O Flyway roda como o usuario 'astral'
#   (dono do schema via /etc/astral/astral.env), que NAO e' superusuario, e
#   CREATE EXTENSION timescaledb nao e' trusted -- ou seja, dentro da migracao
#   a V100 falharia na primeira subida. Aqui roda como postgres, que pode.
#   As tabelas comuns continuam sendo da migration; a conversao e' deste script.
#
# POR QUE 30 DIAS
#   E' o horizonte em que um pico de sexta ainda e' comparavel ao da sexta
#   seguinte. drop_chunks e' o que impede a serie de virar incidente de disco:
#   ~5.760 linhas/dia por serie x 4 serie x 30 dias ~ 700k linhas -- leve para
#   Timescale, e continuo assim porque a retencao apagou o resto.
#
# IDEMPOTENTE: tudo aqui e' IF NOT EXISTS ou if_not_exists => true. Rodar
# dezenas de vezes nao duplica hypertable nem recria politica.
#
# Uso: sudo scripts/configure-timescaledb.sh
#      DIAS=60 sudo -E scripts/configure-timescaledb.sh      # outra retencao
set -Eeuo pipefail

DB="${ASTRAL_DB:-astral}"
# STRING, nao array. As chamadas sao `$SP "$PSQL_DB -tAc \"SQL\""`, ou seja,
# o valor inteiro vira UM unico argumento de `su - postgres -c`. Com array,
# "$PSQL_DB" expande so' para o elemento [0] ("psql") e o `-d "$DB" se perdia:
# TODAS as consultas rodavam no banco padrao do usuario postgres, que neste
# servidor e' o banco 'postgres' -- onde a extensao timescaledb ESTA
# instalada e as tabelas metric_* NAO existem. Resultado observado em
# 04/10/2026: "[OK] extensao ja instalada" (falso) e, no mesmo banco, "tabela
# metric_cpu nao existe" (falso). Duas mentiras de um mesmo bug.
PSQL_DB="psql -X -q -v ON_ERROR_STOP=1 -d $DB"
SP="su - postgres -c"
DIAS="${ASTRAL_RETENTION_DAYS:-30}"
# Tabelas que viram hypertable. O nome e' o contrato com a V100: se a
# migration criar outra, este script tem que acompanhar.
TABELAS=(metric_cpu metric_memory metric_io metric_network)
MARCADOR="# Astral TimescaleDB BEGIN"

command -v psql >/dev/null || { echo "[ERRO] psql ausente."; exit 1; }

SUDO=""
[[ $EUID -ne 0 ]] && SUDO="sudo"

# ---------------------------------------------------------------------------
# 1) shared_preload_libraries -- a extensao so' carrega se o postgres reiniciar
#    com ela. Este bloco ja' existe em instalacoes anteriores; o grep decide.
# ---------------------------------------------------------------------------
CFG="$($SP "$PSQL_DB -tAc 'show config_file'" | tr -d ' \r')"
[[ -f "$CFG" ]] || { echo "[ERRO] postgresql.conf nao encontrado ($CFG)."; exit 1; }

# Escrever no postgresql.conf e' a operacao mais perigosa deste script: um
# parametro que nao existe faz o servidor se recusar a subir, e o sintoma
# ("Job for postgresql.service failed") nao diz a causa. Este helper so'
# escreve o que o POSTGRES EM EXECUCAO reconhece como parametro. Servidor
# parado? Nao escreve -- melhor nao mexer do que quebrar o boot.
PRECISA_RESTART=0
add_param() {
  local nome="$1" valor="$2" desc="$3"
  grep -qE "^${nome}[[:space:]]*=" "$CFG" && return 0
  local conhecido
  conhecido="$($SP "$PSQL_DB -tAc \"select count(*) from pg_settings where name='${nome}'\"" 2>/dev/null | tr -d ' \r')"
  if [[ "$conhecido" != "1" ]]; then
    echo "[AVISO] '${nome}' nao e' parametro deste PostgreSQL; nao escrito."
    return 0
  fi
  echo "[INFO] $desc"
  echo "${nome} = ${valor}" >>"$CFG"
  PRECISA_RESTART=1
}

if ! grep -q "shared_preload_libraries = 'timescaledb'" "$CFG"; then
  echo "[INFO] habilitando timescaledb em shared_preload_libraries."
  cp -a "$CFG" "$CFG.astral.bak.$(date +%s)"
  cat >>"$CFG" <<EOF

$MARCADOR BEGIN -- gerado por scripts/configure-timescaledb.sh. Nao editar a mao.
shared_preload_libraries = 'timescaledb'
timescaledb.telemetry_level = off
$MARCADOR END
EOF
  PRECISA_RESTART=1
fi

# Cada job de fundo do TimescaleDB (retencao, compactacao) ocupa um worker do
# POSTGRES. O PostgreSQL 18 NAO tem "max_background_workers" -- esse nome nao
# existe e o servidor se recusa a subir com ele no arquivo (aconteceu em
# 04/10 e e' por isso que ha validacao antes de reiniciar). O nome certo e'
# max_worker_processes.
add_param max_worker_processes 16 "max_worker_processes = 16 (folga para os jobs do TimescaleDB)."

if [[ $PRECISA_RESTART -eq 1 ]]; then
  echo "[INFO] postgresql.conf mudou; reiniciando postgres."
  $SUDO systemctl restart postgresql-18 2>/dev/null || $SUDO systemctl restart postgresql
  for i in $(seq 1 30); do
    $SP "$PSQL_DB -tAc 'select 1'" >/dev/null 2>&1 && break
    sleep 1
  done
fi

# ---------------------------------------------------------------------------
# 2) extensao no banco 'astral'
# ---------------------------------------------------------------------------
if $SP "$PSQL_DB -tAc \"select count(*) from pg_available_extensions where name='timescaledb'\"" | grep -q '^1$'; then
  if $SP "$PSQL_DB -tAc \"select count(*) from pg_extension where extname='timescaledb'\"" | grep -q '^0$'; then
    echo "[INFO] criando a extensao timescaledb no banco $DB."
    $SP "$PSQL_DB -c 'CREATE EXTENSION IF NOT EXISTS timescaledb'"
  else
    echo "[OK] extensao timescaledb ja' instalada no banco $DB."
  fi
else
  echo "[ERRO] timescaledb nao esta' em pg_available_extensions. Pacote instalado?"
  rpm -qa | grep -i timescale || echo "       (rpm timescaledb ausente)"
  exit 1
fi

# ---------------------------------------------------------------------------
# 3) hypertables + retencao
# ---------------------------------------------------------------------------
# Cada passo e' TOLERADO e CONTADO; o veredito e' no fim. Antes, um
# add_retention_policy recusado derrubava o script no meio do loop por causa
# de 'set -e': metric_cpu virava hypertable e as outras tres ficavam para
# sempre pendentes -- meio script cumprido, com saida que parecia sucesso.
RETENCAO_NATIVA=1
FALHAS=0
AUSENTES=0
HYPER_OK=0
POL_OK=0
EXISTENTES=()

for T in "${TABELAS[@]}"; do
  EXISTE=$($SP "$PSQL_DB -tAc \"select count(*) from information_schema.tables where table_schema='public' and table_name='$T'\"")
  if [[ "$EXISTE" != "1" ]]; then
    echo "[AVISO] tabela $T nao existe ainda (a migration V100 roda na subida da aplicacao)."
    echo "        Ela sera' convertida na proxima execucao deste script."
    AUSENTES=$((AUSENTES + 1))
    continue
  fi
  EXISTENTES+=("$T")

  JA_HYPER=$($SP "$PSQL_DB -tAc \"select count(*) from timescaledb_information.hypertables where hypertable_name='$T'\"")
  if [[ "$JA_HYPER" == "0" ]]; then
    echo "[INFO] convertendo $T em hypertable (chunk de 1 dia)."
    # chunk de 1 dia: e' o intervalo que casa com a retencao de 30 dias (30
    # chunks vivos por serie) e permite apagar um dia inteiro de uma vez.
    if SAIDA=$($SP "$PSQL_DB -c \"SELECT create_hypertable('public.$T', 'ts', chunk_time_interval => INTERVAL '1 day', if_not_exists => TRUE)\"" 2>&1); then
      HYPER_OK=$((HYPER_OK + 1))
    else
      echo "[ERRO] $T nao virou hypertable:"
      echo "$SAIDA" | sed 's/^/        /'
      FALHAS=$((FALHAS + 1))
      continue
    fi
  else
    echo "[OK] $T ja' e' hypertable."
    HYPER_OK=$((HYPER_OK + 1))
  fi

  # A retencao e' um JOB do TimescaleDB, nao uma view propria: o TimescaleDB
  # 2.27 nao expoe timescaledb_information.retention_policies (aqui a consulta
  # dava "relacao nao existe" e, pior, o erro era engolido pelo '|| true' e
  # escondia que a politica tambem nao existia). O jobs traz alvo, config e
  # agenda -- e e' ele que prova que a retencao esta' de fato agendada.
  JA_POL=$($SP "$PSQL_DB -tAc \"select count(*) from timescaledb_information.jobs where proc_name='policy_retention' and hypertable_name='$T'\"")
  if [[ "$JA_POL" == "0" ]]; then
    echo "[INFO] politica de retencao de ${DIAS} dias em $T."
    if SAIDA=$($SP "$PSQL_DB -c \"SELECT add_retention_policy('public.$T', INTERVAL '$DIAS days', if_not_exists => TRUE)\"" 2>&1); then
      POL_OK=$((POL_OK + 1))
      [[ -n "$SAIDA" ]] && echo "$SAIDA"
    else
      RETENCAO_NATIVA=0
      echo "[AVISO] $T: add_retention_policy recusado neste build do TimescaleDB."
      echo "$SAIDA" | sed 's/^/         /'
    fi
  else
    echo "[OK] retencao de ${DIAS} dias ja' ativa em $T."
    POL_OK=$((POL_OK + 1))
  fi
done

# ---------------------------------------------------------------------------
# 3b) plano B -- timer de drop_chunks
#
# add_retention_policy e' recurso da licenca Timescale (TSL/comunitaria). O
# pacote deste servidor e' o do Fedora (timescaledb-2.27.1-1.fc44) e traz
# APENAS LICENSE-APACHE: a funcao existe e devolve
#   "add_retention_policy is not supported under the current apache license".
# Instalar o TSL seria trocar a licenca de um pacote do sistema por conta
# propria; aqui preferimos o caminho que nao muda pacote nenhum: um timer do
# systemd rodando drop_chunks, que e' Apache e ja' foi testado neste build.
# A retencao de 30 dias acontece igual -- so' muda quem dispara.
# ---------------------------------------------------------------------------
TIMER_SVC=/etc/systemd/system/astral-timescale-retention.service
TIMER_TMR=/etc/systemd/system/astral-timescale-retention.timer

if [[ $RETENCAO_NATIVA -eq 1 ]]; then
  if [[ -f "$TIMER_TMR" ]]; then
    echo "[INFO] retencao nativa agora disponivel: removendo o timer de fallback."
    $SUDO systemctl disable --now astral-timescale-retention.timer >/dev/null 2>&1 || true
    $SUDO rm -f "$TIMER_SVC" "$TIMER_TMR"
    $SUDO systemctl daemon-reload
  fi
elif [[ ${#EXISTENTES[@]} -eq 0 ]]; then
  echo "[AVISO] nenhuma hypertable ainda: sem retencao nativa e sem timer."
elif [[ $EUID -ne 0 ]]; then
  echo "[AVISO] retencao nativa indisponivel e o script nao roda como root:"
  echo "        o timer de drop_chunks NAO foi instalado. Rode com sudo."
else
  SQL_DROP=""
  for t in "${EXISTENTES[@]}"; do
    SQL_DROP+="SELECT drop_chunks('$t', older_than => INTERVAL '${DIAS} days'); "
  done
  cat >"$TIMER_SVC" <<EOF
[Unit]
Description=Retencao de ${DIAS} dias das metricas da Astral (drop_chunks)
Documentation=file:///home/euripedes/astral-plataform-hci/scripts/configure-timescaledb.sh
After=postgresql.service

[Service]
Type=oneshot
User=postgres
# drop_chunks e' Apache neste build; a politica nativa (TSL) nao existe aqui.
# Tolerar tabela ausente: se a V100 ainda nao rodou, o timer nao pode virar
# motivo de alarme -- ele apenas nao tem o que apagar.
ExecStart=/usr/bin/psql -X -q -v ON_ERROR_STOP=1 -d ${DB} -c "${SQL_DROP}"
EOF
  cat >"$TIMER_TMR" <<'EOF'
[Unit]
Description=Agenda diaria da retencao das metricas da Astral

[Timer]
OnCalendar=daily
# Espalha a carga: quatro hypertables apagando ao mesmo tempo em todo host
# nao e' um problema aqui, mas o custo e' o mesmo de espalhar.
RandomizedDelaySec=45m
Persistent=true

[Install]
WantedBy=timers.target
EOF
  $SUDO systemctl daemon-reload
  $SUDO systemctl enable --now astral-timescale-retention.timer
  echo "[OK] timer de retencao instalado (drop_chunks diario, ${DIAS} dias)."
fi

# ---------------------------------------------------------------------------
# 4) prova: o que existe, o que foi apagado e quando a proxima coleta roda
# ---------------------------------------------------------------------------
echo
echo "=== hypertables ==="
$SP "$PSQL_DB -c \"SELECT h.hypertable_name AS tabela,
       coalesce(c.chunks, 0) AS chunks_vivos
  FROM timescaledb_information.hypertables h
  LEFT JOIN (SELECT hypertable_name, count(*) AS chunks
               FROM timescaledb_information.chunks GROUP BY 1) c
         ON c.hypertable_name = h.hypertable_name
 WHERE h.hypertable_name IN ('metric_cpu','metric_memory','metric_io','metric_network')
 ORDER BY 1\"" 2>&1 || true

echo
echo "=== retencao: jobs policy_retention ==="
$SP "$PSQL_DB -c \"SELECT j.hypertable_name AS tabela,
       j.config->>'drop_after' AS retencao,
       j.scheduled             AS ativo,
       j.schedule_interval     AS frequencia
  FROM timescaledb_information.jobs j
 WHERE j.proc_name = 'policy_retention'
 ORDER BY 1\"" 2>&1 || true

if [[ -f "$TIMER_TMR" ]]; then
  echo
  echo "=== retencao: timer (plano B) ==="
  $SUDO systemctl list-timers astral-timescale-retention.timer --no-pager 2>/dev/null || true
fi

# drop_chunks manual: existe para o operador que precisa liberar disco HOJE,
# sem esperar o job. A retencao automatica faz a mesma coisa, so' que no hora.
echo
echo "=== apagando agora (opcional, se o disco apertar) ==="
for t in "${TABELAS[@]}"; do
  echo "    sudo -u postgres psql -d $DB -c \"SELECT drop_chunks('$t', older_than => INTERVAL '${DIAS} days');\""
done

echo
# Veredito HONESTO. Dizer "pronto" com zero hypertables convertidas e' o que
# escondeu o bug do '-d' perdido: o script saia com [OK] sem ter feito nada.
if [[ $FALHAS -gt 0 ]]; then
  echo "[ERRO] $FALHAS tabela(s) falharam na conversao; veja o erro acima."
  exit 1
elif [[ $AUSENTES -gt 0 || $HYPER_OK -ne ${#TABELAS[@]} ]]; then
  echo "[AVISO] $HYPER_OK/${#TABELAS[@]} hypertables prontas; $AUSENTES ainda nao existem."
  echo "        As tabelas nascem na V100 -- suba a aplicacao e rode este"
  echo "        script de novo. Ele e' idempotente."
  exit 1
elif [[ $RETENCAO_NATIVA -eq 1 ]]; then
  echo "[OK] TimescaleDB pronto: $HYPER_OK/${#TABELAS[@]} hypertables, retencao nativa de ${DIAS} dias em $POL_OK tabela(s)."
else
  echo "[OK] TimescaleDB pronto: $HYPER_OK/${#TABELAS[@]} hypertables, retencao de ${DIAS} dias via timer (drop_chunks diario)."
  echo "     Motivo: o build Apache do pacote nao tem add_retention_policy (TSL)."
fi
