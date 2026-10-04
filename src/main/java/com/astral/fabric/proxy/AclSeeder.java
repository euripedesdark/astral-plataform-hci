package com.astral.fabric.proxy;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Seeding automatico das categorias de ACL no PostgreSQL.
 *
 * <p><b>Por que automatico e nao migration:</b> a lista de categorias e' dado,
 * nao schema. Colocar dados em migration obriga a editar um arquivo versionado
 * toda vez que o operador acrescentar uma categoria, e em seguida reimplantar.
 * Rodar na subida faz a plataforma chegar pronta em qualquer host novo, com o
 * mesmo vocabulario -- que e' a condicao para o PASSO B ter o que ler.
 *
 * <p><b>Idempotente</b>: grava so' o que nao existe, nunca sobrescreve o que o
 * operador editou. Subir dez vezes nao duplica nada.
 *
 * <p>A lista vem das categorias que ja' existiam em
 * {@code fabric/acess-report-system/backend/config.py}
 * ({@code CATEGORIAS_EXCLUIDAS}) -- mesma origem, mesmo nome, para que um
 * relatorio legado e a nova engine falem a mesma lingua.
 */
@Component
public class AclSeeder {

    /** code, name, descricao, bloqueada por padrao. */
    private static final Object[][] PADRAO = {
            {"adult",            "Adulto / Conteudo sexual",        "Pornografia, nudez, conteudo adulto", true},
            {"mixed_adult",      "Adulto misto",                    "Conteudo com secao adulta misturada", true},
            {"dating",           "Relacionamentos / Sites de encontro", "Sites de namoro e sugar dating", false},
            {"gambling",         "Apostas / Cassino online",        "Cassino, apostas esportivas, loterias", true},
            {"drogue",           "Drogas / Farmacos ilicitos",      "Venda e promocao de substancias ilegais", true},
            {"agressif",         "Violencia / Conteudo agressivo",  "Gore, violencia grafica, incitamento", true},
            {"warez",            "Pirataria / Warez",               "Cracks, keygens, torrents ilegais", true},
            {"phishing",         "Phishing / Golpe",                "Fraude, roubo de credencial, clonagem", true},
            {"malware",          "Malware / Codigo malicioso",      "Downloads infectados, C2, exploit kit", true},
            {"hacking",          "Hacking / Ferramentas ofensivas", "Pentest sem autorizacao, keylogger", false},
            {"ddos",             "DDoS / Ataque",                   "Botnet, booter, ferramentas de ataque", true},
            {"cryptojacking",    "Cryptojacking",                   "Mineracao forcada de criptomoedas", true},
            {"stalkerware",      "Stalkerware",                     "Monitoramento de pessoas sem consentimento", true},
            {"vpn",              "VPN / Proxy",                     "Anonimato e fuga de politica de origem", false},
            {"redirector",       "Redirecionador",                  "Encurtadores e cloaks de URL", false},
            {"strict_redirector","Redirecionador restrito",        "Redirecionamento agressivo de URL", true},
            {"strong_redirector","Redirecionador forte",           "Redirect que esconde o destino final", true},
            {"chat",             "Mensagens / Chat",                "IM, rooms, conversas em tempo real", false},
            {"social_networks",  "Redes sociais",                   "Facebook, Instagram, X, TikTok, LinkedIn", false},
            {"games",            "Jogos",                           "Lojas, streaming e portais de jogos", false},
            {"lingerie",         "Moda intima",                     "Vestuario e conteudo de lingerie", false},
            {"streaming",        "Streaming de video",              "Netflix, YouTube, Twitch e afins", false},
            {"noticias",         "Noticias / Imprensa",             "Jornais, revistas, portais de noticias", false},
            {"saude",            "Saude",                           "Portal do paciente, telemedicina", false},
            {"educacao",         "Educacao",                        "LMS, cursos, repositorios academicos", false},
            {"compras",          "Comercio eletronico",             "Lojas online e marketplaces", false},
            {"nuvem",            "Nuvem / Storage",                 "Drive, S3, Dropbox, OneDrive", false},
            {"conferencia",      "Videoconferencia",                "Meet, Zoom, Teams", false},
            {"admin",            "Infraestrutura / Admin",          "Painéis e servicos de administracao", true},
    };

    private final AclCategoryRepo categorias;
    private final AclDomainRepo dominios;
    private final AclDomainOverrideRepo overrides;
    private final AclRuleRepo regras;

    public AclSeeder(AclCategoryRepo categorias, AclDomainRepo dominios,
                     AclDomainOverrideRepo overrides, AclRuleRepo regras) {
        this.categorias = categorias;
        this.dominios = dominios;
        this.overrides = overrides;
        this.regras = regras;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void semeia() {
        int novas = 0;
        for (Object[] l : PADRAO) {
            String code = (String) l[0];
            if (categorias.findByCode(code).isPresent()) continue;
            categorias.save(new AclCategory(code, (String) l[1], (String) l[2], (Boolean) l[3]));
            novas++;
        }

        // Excecoes que precisam existir antes da primeira requisicao, senao o
        // PASSO A roda com a tabela vazia e nao ha por onde passar.
        if (overrides.count() == 0) {
            AclDomainOverride infra = new AclDomainOverride();
            infra.pattern = "*.srvcloud.cloud";
            infra.action = AclDomainOverride.ALLOW;
            infra.reason = "Dominio proprio: nunca deve cair em politica de conteudo";
            infra.createdBy = "seed";
            overrides.save(infra);

            AclDomainOverride svc = new AclDomainOverride();
            svc.pattern = "auth.srvcloud.cloud";
            svc.action = AclDomainOverride.BYPASS;
            svc.reason = "Bypass do Auth Service: bloquear aqui mata o proprio login";
            svc.createdBy = "seed";
            overrides.save(svc);
        }

        // Padrao: NADA e permitido sem regra explicita. Fail closed e' a regra
        // da casa; uma tabela de regras vazia tem que NEGAR, nao liberar.
        if (regras.count() == 0) {
            categorias.findByCode("social_networks").ifPresent(c ->
                    regras.save(new AclRule(c, "Domain Users", AclRule.ALLOW, 200)));
        }

        // Exemplo de dominio so' para a tela nao nascer vazia e o operador
        // entender o formato aceito. Removivel pelo CRUD sem prejuizo.
        if (dominios.count() == 0) {
            categorias.findByCode("social_networks").ifPresent(c -> {
                dominios.save(new AclDomain("facebook.com", c, "seed"));
                dominios.save(new AclDomain("*.facebook.com", c, "seed"));
                dominios.save(new AclDomain("instagram.com", c, "seed"));
            });
            categorias.findByCode("gambling").ifPresent(c ->
                    dominios.save(new AclDomain("*.bet365.com", c, "seed")));
        }

        if (novas > 0 || dominios.count() == 0) {
            org.slf4j.LoggerFactory.getLogger(AclSeeder.class)
                    .info("acl seeding: {} categorias criadas, {} dominios, {} excecoes, {} regras",
                            novas, dominios.count(), overrides.count(), regras.count());
        }
    }

    public List<String> codigos() {
        return categorias.findAll().stream().map(c -> c.code).sorted().toList();
    }
}
