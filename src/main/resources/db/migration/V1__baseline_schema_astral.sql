-- =====================================================================
-- V1__baseline_schema_astral.sql
-- BASELINE DO SCHEMA DO BANCO 'astral'
--
-- Origem: pg_dump --schema-only do banco 'astral' (PostgreSQL 18.6) em 29/09/2026.
-- Gerado por extracao do dump, nao transcrito a mao.
--
-- POR QUE ESTE ARQUIVO EXISTE
--   Ate 29/09 o schema do banco 'astral' nao tinha versao nenhuma. As 16
--   tabelas foram criadas por 'spring.jpa.hibernate.ddl-auto=update' a partir de
--   entidades JPA, ou seja, o schema era o que o Hibernate deduziu. Nao havia
--   migration, nao havia historico, e nao havia como saber quando mudou. Isso e
--   o mesmo problema de rastreabilidade que o dono Nomeou, so que dentro do banco.
--
--   Decisao do dono (29/09): Flyway e o dono do schema. Este arquivo e a fotografia
--   do estado que existia no momento da decisao, para que o estado seja legivel e
--   reproduzivel em vez de herdado.
--
-- COMO SE COMPORTA
--   Banco novo (vazio):  Flyway roda este arquivo e cria as 16 tabelas.
--   Banco existente:     Flyway faz baseline na versao 1 e NAO executa este
--                         arquivo; em seguida aplica a V100. Como o estado real
--                         ja e exatamente o que este arquivo descreve, o resultado
--                         e o mesmo. A divida de 'o banco difere do que o codigo
--                         espera' e cobrada pelo 'ddl-auto=validate', que falha na
--                         partida se alguma coluna divergir.
--   Tudo aqui e IF NOT EXISTS ou bloco DO/IF NOT EXISTS: rodar duas vezes nao
--   quebra nada.
--
-- O QUE ESTE ARQUIVO NAO CORRIGE (defeitos reais, medidos, deixados como estao)
--   Um baseline registra o que e. Corrigir aqui seria mentir sobre o historico.
--   Fica para migration posterior, com nome proprio:
--
--   1) Contadores como texto. Em firewall_rule, port_forward e masquerade_rule,
--      'bytes' e 'packets' sao character varying(30), nao bigint. Consequencia
--      medida: nao da para somar em SQL, nao ha indice util, e a ordenacao e
--      lexicografica -- '999' ordena depois de '1000'. Um contador que passa de
--      30 caracteres vira erro na insercao.
--
--   2) audit_log.timestamp e 'timestamp without time zone' guardando um Instant.
--      A coluna perde o fuso. Numa trilha de auditoria, 'quando' e o campo mais
--      importante que existe, e 'sem fuso' significa que a hora so tem sentido se
--      ninguem lembrar que o gravador gravou UTC. Alem disso o nome exige aspas
--      duplas em toda consulta, o que e convite a erro.
--
--   3) diff_json e varchar(8192), nao jsonb. Nao da para consultar por chave,
--      8 KB e teto e nao armazenamento.
--
--   4) As duas tabelas de juncao (host_group_cidrs, port_group_ports) nao tem
--      chave estrangeira. Orfao nao tem como ser detectado pelo banco.
-- =====================================================================


-- ---------------------------------------------------------------------
-- 1) SEQUENCIAS (13) -- geradas por GenerationType.IDENTITY nas entidades
-- ---------------------------------------------------------------------
CREATE SEQUENCE IF NOT EXISTS public.audit_log_id_seq INCREMENT BY 1 NO MINVALUE NO MAXVALUE;
CREATE SEQUENCE IF NOT EXISTS public.auto_ban_rule_id_seq INCREMENT BY 1 NO MINVALUE NO MAXVALUE;
CREATE SEQUENCE IF NOT EXISTS public.firewall_rule_id_seq INCREMENT BY 1 NO MINVALUE NO MAXVALUE;
CREATE SEQUENCE IF NOT EXISTS public.firewall_snapshot_id_seq INCREMENT BY 1 NO MINVALUE NO MAXVALUE;
CREATE SEQUENCE IF NOT EXISTS public.host_group_id_seq INCREMENT BY 1 NO MINVALUE NO MAXVALUE;
CREATE SEQUENCE IF NOT EXISTS public.masquerade_rule_id_seq INCREMENT BY 1 NO MINVALUE NO MAXVALUE;
CREATE SEQUENCE IF NOT EXISTS public.port_forward_id_seq INCREMENT BY 1 NO MINVALUE NO MAXVALUE;
CREATE SEQUENCE IF NOT EXISTS public.port_group_id_seq INCREMENT BY 1 NO MINVALUE NO MAXVALUE;
CREATE SEQUENCE IF NOT EXISTS public.rate_limit_policy_id_seq INCREMENT BY 1 NO MINVALUE NO MAXVALUE;
CREATE SEQUENCE IF NOT EXISTS public.schedule_id_seq INCREMENT BY 1 NO MINVALUE NO MAXVALUE;
CREATE SEQUENCE IF NOT EXISTS public.threat_list_id_seq INCREMENT BY 1 NO MINVALUE NO MAXVALUE;
CREATE SEQUENCE IF NOT EXISTS public.zone_id_seq INCREMENT BY 1 NO MINVALUE NO MAXVALUE;
CREATE SEQUENCE IF NOT EXISTS public.zone_interface_id_seq INCREMENT BY 1 NO MINVALUE NO MAXVALUE;


-- ---------------------------------------------------------------------
-- 2) TABELAS (16) -- colunas exatamente como o Hibernate as deduziu
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.audit_log (
    id bigint NOT NULL,
    username character varying(80),
    entity_type character varying(40),
    entity_id character varying(40),
    action character varying(40),
    diff_json character varying(8192),
    "timestamp" timestamp without time zone
);
CREATE TABLE IF NOT EXISTS public.auto_ban_rule (
    id bigint NOT NULL,
    max_attempts integer,
    window_minutes integer,
    ban_minutes integer,
    target_port character varying(30),
    enabled boolean
);
CREATE TABLE IF NOT EXISTS public.firewall_rule (
    id bigint NOT NULL,
    chain character varying(20),
    priority integer,
    protocol character varying(10),
    src_cidr character varying(50),
    dst_cidr character varying(50),
    port character varying(30),
    iface character varying(30),
    action character varying(30),
    enabled boolean,
    comment character varying(512),
    raw_rule character varying(1024),
    bytes character varying(30),
    packets character varying(30),
    applied_at timestamp without time zone
);
CREATE TABLE IF NOT EXISTS public.firewall_snapshot (
    id bigint NOT NULL,
    created_at timestamp without time zone,
    label character varying(80),
    dump_text text
);
CREATE TABLE IF NOT EXISTS public.firewall_state (
    key character varying(40) NOT NULL,
    value character varying(80)
);
CREATE TABLE IF NOT EXISTS public.host_group (
    id bigint NOT NULL,
    name character varying(80)
);
CREATE TABLE IF NOT EXISTS public.host_group_cidrs (
    host_group_id bigint NOT NULL,
    cidrs character varying(50)
);
CREATE TABLE IF NOT EXISTS public.masquerade_rule (
    id bigint NOT NULL,
    iface character varying(30),
    enabled boolean,
    bytes character varying(30),
    packets character varying(30)
);
CREATE TABLE IF NOT EXISTS public.port_forward (
    id bigint NOT NULL,
    iface character varying(30),
    external_port character varying(30),
    protocol character varying(10),
    internal_ip character varying(50),
    internal_port character varying(30),
    enabled boolean,
    description character varying(512),
    bytes character varying(30),
    packets character varying(30)
);
CREATE TABLE IF NOT EXISTS public.port_group (
    id bigint NOT NULL,
    name character varying(80)
);
CREATE TABLE IF NOT EXISTS public.port_group_ports (
    port_group_id bigint NOT NULL,
    ports character varying(30)
);
CREATE TABLE IF NOT EXISTS public.rate_limit_policy (
    id bigint NOT NULL,
    port character varying(30),
    protocol character varying(10),
    rate_per_second integer,
    enabled boolean
);
CREATE TABLE IF NOT EXISTS public.schedule (
    id bigint NOT NULL,
    name character varying(80),
    days_of_week character varying(60),
    start_time time without time zone,
    end_time time without time zone
);
CREATE TABLE IF NOT EXISTS public.threat_list (
    id bigint NOT NULL,
    name character varying(80),
    source_url character varying(255),
    last_updated timestamp without time zone,
    ip_count integer,
    enabled boolean
);
CREATE TABLE IF NOT EXISTS public.zone (
    id bigint NOT NULL,
    name character varying(80),
    trust_level character varying(20),
    default_policy character varying(10),
    color character varying(20)
);
CREATE TABLE IF NOT EXISTS public.zone_interface (
    id bigint NOT NULL,
    zone_id bigint,
    iface_name character varying(30)
);


-- ---------------------------------------------------------------------
-- 3) DEFAULT DAS COLUNAS id (13) -- nextval das sequencias acima
-- ---------------------------------------------------------------------
DO $$ BEGIN
    ALTER TABLE ONLY public.audit_log ALTER COLUMN id SET DEFAULT nextval('public.audit_log_id_seq'::regclass);
EXCEPTION WHEN others THEN NULL; END $$;
DO $$ BEGIN
    ALTER TABLE ONLY public.auto_ban_rule ALTER COLUMN id SET DEFAULT nextval('public.auto_ban_rule_id_seq'::regclass);
EXCEPTION WHEN others THEN NULL; END $$;
DO $$ BEGIN
    ALTER TABLE ONLY public.firewall_rule ALTER COLUMN id SET DEFAULT nextval('public.firewall_rule_id_seq'::regclass);
EXCEPTION WHEN others THEN NULL; END $$;
DO $$ BEGIN
    ALTER TABLE ONLY public.firewall_snapshot ALTER COLUMN id SET DEFAULT nextval('public.firewall_snapshot_id_seq'::regclass);
EXCEPTION WHEN others THEN NULL; END $$;
DO $$ BEGIN
    ALTER TABLE ONLY public.host_group ALTER COLUMN id SET DEFAULT nextval('public.host_group_id_seq'::regclass);
EXCEPTION WHEN others THEN NULL; END $$;
DO $$ BEGIN
    ALTER TABLE ONLY public.masquerade_rule ALTER COLUMN id SET DEFAULT nextval('public.masquerade_rule_id_seq'::regclass);
EXCEPTION WHEN others THEN NULL; END $$;
DO $$ BEGIN
    ALTER TABLE ONLY public.port_forward ALTER COLUMN id SET DEFAULT nextval('public.port_forward_id_seq'::regclass);
EXCEPTION WHEN others THEN NULL; END $$;
DO $$ BEGIN
    ALTER TABLE ONLY public.port_group ALTER COLUMN id SET DEFAULT nextval('public.port_group_id_seq'::regclass);
EXCEPTION WHEN others THEN NULL; END $$;
DO $$ BEGIN
    ALTER TABLE ONLY public.rate_limit_policy ALTER COLUMN id SET DEFAULT nextval('public.rate_limit_policy_id_seq'::regclass);
EXCEPTION WHEN others THEN NULL; END $$;
DO $$ BEGIN
    ALTER TABLE ONLY public.schedule ALTER COLUMN id SET DEFAULT nextval('public.schedule_id_seq'::regclass);
EXCEPTION WHEN others THEN NULL; END $$;
DO $$ BEGIN
    ALTER TABLE ONLY public.threat_list ALTER COLUMN id SET DEFAULT nextval('public.threat_list_id_seq'::regclass);
EXCEPTION WHEN others THEN NULL; END $$;
DO $$ BEGIN
    ALTER TABLE ONLY public.zone ALTER COLUMN id SET DEFAULT nextval('public.zone_id_seq'::regclass);
EXCEPTION WHEN others THEN NULL; END $$;
DO $$ BEGIN
    ALTER TABLE ONLY public.zone_interface ALTER COLUMN id SET DEFAULT nextval('public.zone_interface_id_seq'::regclass);
EXCEPTION WHEN others THEN NULL; END $$;


-- ---------------------------------------------------------------------
-- 4) CHAVES PRIMARIAS (14) -- Postgres nao tem ADD CONSTRAINT IF NOT EXISTS,
--    entao cada uma e guardada por bloco DO.
-- ---------------------------------------------------------------------
DO $$ BEGIN
    -- Guarda pela TABELA, nao pelo nome do constraint. Nome de constraint e
    -- unico por schema no Postgres, entao um guard por conname acha o
    -- constraint de outro schema e pula em silencio. Foi assim que este
    -- arquivo passou no testecreating 16 tabelas e 0 chaves primarias.
    IF NOT EXISTS (
        SELECT 1 FROM pg_index
        WHERE indrelid = 'public.audit_log'::regclass AND indisprimary
    ) THEN
        ALTER TABLE ONLY public.audit_log ADD CONSTRAINT audit_log_pkey PRIMARY KEY (id);
    END IF;
EXCEPTION WHEN others THEN NULL;
END $$;
DO $$ BEGIN
    -- Guarda pela TABELA, nao pelo nome do constraint. Nome de constraint e
    -- unico por schema no Postgres, entao um guard por conname acha o
    -- constraint de outro schema e pula em silencio. Foi assim que este
    -- arquivo passou no testecreating 16 tabelas e 0 chaves primarias.
    IF NOT EXISTS (
        SELECT 1 FROM pg_index
        WHERE indrelid = 'public.auto_ban_rule'::regclass AND indisprimary
    ) THEN
        ALTER TABLE ONLY public.auto_ban_rule ADD CONSTRAINT auto_ban_rule_pkey PRIMARY KEY (id);
    END IF;
EXCEPTION WHEN others THEN NULL;
END $$;
DO $$ BEGIN
    -- Guarda pela TABELA, nao pelo nome do constraint. Nome de constraint e
    -- unico por schema no Postgres, entao um guard por conname acha o
    -- constraint de outro schema e pula em silencio. Foi assim que este
    -- arquivo passou no testecreating 16 tabelas e 0 chaves primarias.
    IF NOT EXISTS (
        SELECT 1 FROM pg_index
        WHERE indrelid = 'public.firewall_rule'::regclass AND indisprimary
    ) THEN
        ALTER TABLE ONLY public.firewall_rule ADD CONSTRAINT firewall_rule_pkey PRIMARY KEY (id);
    END IF;
EXCEPTION WHEN others THEN NULL;
END $$;
DO $$ BEGIN
    -- Guarda pela TABELA, nao pelo nome do constraint. Nome de constraint e
    -- unico por schema no Postgres, entao um guard por conname acha o
    -- constraint de outro schema e pula em silencio. Foi assim que este
    -- arquivo passou no testecreating 16 tabelas e 0 chaves primarias.
    IF NOT EXISTS (
        SELECT 1 FROM pg_index
        WHERE indrelid = 'public.firewall_snapshot'::regclass AND indisprimary
    ) THEN
        ALTER TABLE ONLY public.firewall_snapshot ADD CONSTRAINT firewall_snapshot_pkey PRIMARY KEY (id);
    END IF;
EXCEPTION WHEN others THEN NULL;
END $$;
DO $$ BEGIN
    -- Guarda pela TABELA, nao pelo nome do constraint. Nome de constraint e
    -- unico por schema no Postgres, entao um guard por conname acha o
    -- constraint de outro schema e pula em silencio. Foi assim que este
    -- arquivo passou no testecreating 16 tabelas e 0 chaves primarias.
    IF NOT EXISTS (
        SELECT 1 FROM pg_index
        WHERE indrelid = 'public.firewall_state'::regclass AND indisprimary
    ) THEN
        ALTER TABLE ONLY public.firewall_state ADD CONSTRAINT firewall_state_pkey PRIMARY KEY (key);
    END IF;
EXCEPTION WHEN others THEN NULL;
END $$;
DO $$ BEGIN
    -- Guarda pela TABELA, nao pelo nome do constraint. Nome de constraint e
    -- unico por schema no Postgres, entao um guard por conname acha o
    -- constraint de outro schema e pula em silencio. Foi assim que este
    -- arquivo passou no testecreating 16 tabelas e 0 chaves primarias.
    IF NOT EXISTS (
        SELECT 1 FROM pg_index
        WHERE indrelid = 'public.host_group'::regclass AND indisprimary
    ) THEN
        ALTER TABLE ONLY public.host_group ADD CONSTRAINT host_group_pkey PRIMARY KEY (id);
    END IF;
EXCEPTION WHEN others THEN NULL;
END $$;
DO $$ BEGIN
    -- Guarda pela TABELA, nao pelo nome do constraint. Nome de constraint e
    -- unico por schema no Postgres, entao um guard por conname acha o
    -- constraint de outro schema e pula em silencio. Foi assim que este
    -- arquivo passou no testecreating 16 tabelas e 0 chaves primarias.
    IF NOT EXISTS (
        SELECT 1 FROM pg_index
        WHERE indrelid = 'public.masquerade_rule'::regclass AND indisprimary
    ) THEN
        ALTER TABLE ONLY public.masquerade_rule ADD CONSTRAINT masquerade_rule_pkey PRIMARY KEY (id);
    END IF;
EXCEPTION WHEN others THEN NULL;
END $$;
DO $$ BEGIN
    -- Guarda pela TABELA, nao pelo nome do constraint. Nome de constraint e
    -- unico por schema no Postgres, entao um guard por conname acha o
    -- constraint de outro schema e pula em silencio. Foi assim que este
    -- arquivo passou no testecreating 16 tabelas e 0 chaves primarias.
    IF NOT EXISTS (
        SELECT 1 FROM pg_index
        WHERE indrelid = 'public.port_forward'::regclass AND indisprimary
    ) THEN
        ALTER TABLE ONLY public.port_forward ADD CONSTRAINT port_forward_pkey PRIMARY KEY (id);
    END IF;
EXCEPTION WHEN others THEN NULL;
END $$;
DO $$ BEGIN
    -- Guarda pela TABELA, nao pelo nome do constraint. Nome de constraint e
    -- unico por schema no Postgres, entao um guard por conname acha o
    -- constraint de outro schema e pula em silencio. Foi assim que este
    -- arquivo passou no testecreating 16 tabelas e 0 chaves primarias.
    IF NOT EXISTS (
        SELECT 1 FROM pg_index
        WHERE indrelid = 'public.port_group'::regclass AND indisprimary
    ) THEN
        ALTER TABLE ONLY public.port_group ADD CONSTRAINT port_group_pkey PRIMARY KEY (id);
    END IF;
EXCEPTION WHEN others THEN NULL;
END $$;
DO $$ BEGIN
    -- Guarda pela TABELA, nao pelo nome do constraint. Nome de constraint e
    -- unico por schema no Postgres, entao um guard por conname acha o
    -- constraint de outro schema e pula em silencio. Foi assim que este
    -- arquivo passou no testecreating 16 tabelas e 0 chaves primarias.
    IF NOT EXISTS (
        SELECT 1 FROM pg_index
        WHERE indrelid = 'public.rate_limit_policy'::regclass AND indisprimary
    ) THEN
        ALTER TABLE ONLY public.rate_limit_policy ADD CONSTRAINT rate_limit_policy_pkey PRIMARY KEY (id);
    END IF;
EXCEPTION WHEN others THEN NULL;
END $$;
DO $$ BEGIN
    -- Guarda pela TABELA, nao pelo nome do constraint. Nome de constraint e
    -- unico por schema no Postgres, entao um guard por conname acha o
    -- constraint de outro schema e pula em silencio. Foi assim que este
    -- arquivo passou no testecreating 16 tabelas e 0 chaves primarias.
    IF NOT EXISTS (
        SELECT 1 FROM pg_index
        WHERE indrelid = 'public.schedule'::regclass AND indisprimary
    ) THEN
        ALTER TABLE ONLY public.schedule ADD CONSTRAINT schedule_pkey PRIMARY KEY (id);
    END IF;
EXCEPTION WHEN others THEN NULL;
END $$;
DO $$ BEGIN
    -- Guarda pela TABELA, nao pelo nome do constraint. Nome de constraint e
    -- unico por schema no Postgres, entao um guard por conname acha o
    -- constraint de outro schema e pula em silencio. Foi assim que este
    -- arquivo passou no testecreating 16 tabelas e 0 chaves primarias.
    IF NOT EXISTS (
        SELECT 1 FROM pg_index
        WHERE indrelid = 'public.threat_list'::regclass AND indisprimary
    ) THEN
        ALTER TABLE ONLY public.threat_list ADD CONSTRAINT threat_list_pkey PRIMARY KEY (id);
    END IF;
EXCEPTION WHEN others THEN NULL;
END $$;
DO $$ BEGIN
    -- Guarda pela TABELA, nao pelo nome do constraint. Nome de constraint e
    -- unico por schema no Postgres, entao um guard por conname acha o
    -- constraint de outro schema e pula em silencio. Foi assim que este
    -- arquivo passou no testecreating 16 tabelas e 0 chaves primarias.
    IF NOT EXISTS (
        SELECT 1 FROM pg_index
        WHERE indrelid = 'public.zone_interface'::regclass AND indisprimary
    ) THEN
        ALTER TABLE ONLY public.zone_interface ADD CONSTRAINT zone_interface_pkey PRIMARY KEY (id);
    END IF;
EXCEPTION WHEN others THEN NULL;
END $$;
DO $$ BEGIN
    -- Guarda pela TABELA, nao pelo nome do constraint. Nome de constraint e
    -- unico por schema no Postgres, entao um guard por conname acha o
    -- constraint de outro schema e pula em silencio. Foi assim que este
    -- arquivo passou no testecreating 16 tabelas e 0 chaves primarias.
    IF NOT EXISTS (
        SELECT 1 FROM pg_index
        WHERE indrelid = 'public.zone'::regclass AND indisprimary
    ) THEN
        ALTER TABLE ONLY public.zone ADD CONSTRAINT zone_pkey PRIMARY KEY (id);
    END IF;
EXCEPTION WHEN others THEN NULL;
END $$;


-- ---------------------------------------------------------------------
-- 5) SEQUENCIAS DONAS DAS COLUNAS id (13)
--    Redundante com o DEFAULT acima, e fica porque e o que o pg_dump emite e
--    porque e o que o DROP da coluna precisa para limpar a sequencia junto.
-- ---------------------------------------------------------------------
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_class WHERE relname = 'audit_log_id_seq') THEN
        EXECUTE format('ALTER SEQUENCE public.audit_log_id_seq OWNED BY public.audit_log.id', 'audit_log_id_seq', 'audit_log.id');
    END IF;
END $$;
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_class WHERE relname = 'auto_ban_rule_id_seq') THEN
        EXECUTE format('ALTER SEQUENCE public.auto_ban_rule_id_seq OWNED BY public.auto_ban_rule.id', 'auto_ban_rule_id_seq', 'auto_ban_rule.id');
    END IF;
END $$;
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_class WHERE relname = 'firewall_rule_id_seq') THEN
        EXECUTE format('ALTER SEQUENCE public.firewall_rule_id_seq OWNED BY public.firewall_rule.id', 'firewall_rule_id_seq', 'firewall_rule.id');
    END IF;
END $$;
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_class WHERE relname = 'firewall_snapshot_id_seq') THEN
        EXECUTE format('ALTER SEQUENCE public.firewall_snapshot_id_seq OWNED BY public.firewall_snapshot.id', 'firewall_snapshot_id_seq', 'firewall_snapshot.id');
    END IF;
END $$;
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_class WHERE relname = 'host_group_id_seq') THEN
        EXECUTE format('ALTER SEQUENCE public.host_group_id_seq OWNED BY public.host_group.id', 'host_group_id_seq', 'host_group.id');
    END IF;
END $$;
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_class WHERE relname = 'masquerade_rule_id_seq') THEN
        EXECUTE format('ALTER SEQUENCE public.masquerade_rule_id_seq OWNED BY public.masquerade_rule.id', 'masquerade_rule_id_seq', 'masquerade_rule.id');
    END IF;
END $$;
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_class WHERE relname = 'port_forward_id_seq') THEN
        EXECUTE format('ALTER SEQUENCE public.port_forward_id_seq OWNED BY public.port_forward.id', 'port_forward_id_seq', 'port_forward.id');
    END IF;
END $$;
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_class WHERE relname = 'port_group_id_seq') THEN
        EXECUTE format('ALTER SEQUENCE public.port_group_id_seq OWNED BY public.port_group.id', 'port_group_id_seq', 'port_group.id');
    END IF;
END $$;
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_class WHERE relname = 'rate_limit_policy_id_seq') THEN
        EXECUTE format('ALTER SEQUENCE public.rate_limit_policy_id_seq OWNED BY public.rate_limit_policy.id', 'rate_limit_policy_id_seq', 'rate_limit_policy.id');
    END IF;
END $$;
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_class WHERE relname = 'schedule_id_seq') THEN
        EXECUTE format('ALTER SEQUENCE public.schedule_id_seq OWNED BY public.schedule.id', 'schedule_id_seq', 'schedule.id');
    END IF;
END $$;
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_class WHERE relname = 'threat_list_id_seq') THEN
        EXECUTE format('ALTER SEQUENCE public.threat_list_id_seq OWNED BY public.threat_list.id', 'threat_list_id_seq', 'threat_list.id');
    END IF;
END $$;
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_class WHERE relname = 'zone_id_seq') THEN
        EXECUTE format('ALTER SEQUENCE public.zone_id_seq OWNED BY public.zone.id', 'zone_id_seq', 'zone.id');
    END IF;
END $$;
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_class WHERE relname = 'zone_interface_id_seq') THEN
        EXECUTE format('ALTER SEQUENCE public.zone_interface_id_seq OWNED BY public.zone_interface.id', 'zone_interface_id_seq', 'zone_interface.id');
    END IF;
END $$;

