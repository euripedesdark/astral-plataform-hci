# Contribuer à Astral Platform & HCI

Merci de votre intérêt pour Astral Platform & HCI ! Ce document fournit les
directives pour contribuer au projet.

## Code de Conduite

Ce projet et tous les participants sont régis par le
[Code de Conduite](../../CODE_OF_CONDUCT.md). En participant, vous êtes censé
respecter ce code.

## Comment puis-je contribuer ?

### Signaler des bugs

Avant de créer un rapport de bug, vérifiez les issues existantes : le problème
a peut-être déjà été signalé. En créant un rapport, incluez autant de détails
que possible :

* Un titre clair et descriptif
* Les étapes exactes pour reproduire le problème
* Le comportement observé après avoir suivi ces étapes
* Le comportement attendu
* Des captures d'écran, le cas échéant
* Votre environnement (distribution et version, Java, Node, navigateur, etc.)

> 💡 Ce projet contrôle le réseau, le pare-feu et l'identité. Lors du signalement
> d'un bug de pare-feu ou de DNS, incluez toujours le `diff` qu'Astral a produit
> entre l'état souhaité et l'état observé. C'est l'information la plus précieuse
> disponible ici.

### Suggérer des améliorations

Les suggestions sont suivies comme issues GitHub. En en créant une, incluez :

* Un titre clair et descriptif
* Une description détaillée de l'amélioration proposée
* Tout exemple pertinent
* La motivation de l'amélioration

### Pull Requests

1. Forkez le dépôt et créez votre branche à partir de `main`.
2. Si vous avez ajouté du code qui doit être testé, ajoutez des tests.
3. Si vous avez modifié des API ou des contrats, mettez la documentation à jour.
4. Vérifiez que la suite de tests passe.
5. Assurez-vous que votre code suit le style de code existant.
6. Créez une pull request avec un titre et une description clairs.

## ⚠️ Règles non négociables

Ces trois règles découlent de décisions d'architecture déjà prises. Une PR qui
les enfreint sera refusée, même si le code est correct.

### 1. L'installateur n'écrit jamais de code source

`scripts/install-astral.sh` est le **seul** installateur. C'est du bash pur et
il ne génère **jamais** de `pom.xml` ni de fichier `.java`.

> **Pourquoi :** un installateur qui écrit le code qu'il compile lui-même écrase
> du code fonctionnel avec du code mort. Le code généré va dans le dépôt et
> passe en revue.

Si votre PR a besoin de générer du code, le code généré va dans le dépôt.

### 2. `ddl-auto` vaut `none`, exprès

N'ajoutez pas d'`@Entity` sans migration explicite.

> **Pourquoi :** le propriétaire du schéma de la base `astral` est le module
> `astral-firewall`. Le plan de contrôle ne lance pas Flyway parce que deux
> processus migrant la même base se disputent la même table d'historique.

### 3. Il n'y a pas de JWT

La session est avec état, côté serveur, avec un cookie `HttpOnly`.
N'introduisez pas d'authentification par jeton sans avoir d'abord discuté du
changement de modèle — ATS et Nginx dépendent de l'en-tête de proxy de
confiance.

## 🛠 Configuration de développement

### Prérequis

* Java 21 (Oracle JDK ou OpenJDK/Temurin)
* Maven 3.9+
* Node.js 20+
* PostgreSQL 15+
* Python 3.10+ (pour les scripts de `fabric/`)

### Compilation

```bash
# Backend
mvn clean package -DskipTests

# Frontend
cd frontend && npm install && npm run build
```

### Exécution des tests

```bash
mvn test
```

### Exécution en local

```bash
# Infrastructure
sudo ./installbase.sh

# Application
mvn spring-boot:run
```

### Vérification de l'installation

```bash
./scripts/verify-astral.sh
```

## Structure du projet

```
astral-plataform-hci/
├── src/main/java/com/astral/
│   ├── main/              # plan de contrôle
│   │   ├── controller/    # Login, Home, SPA forward, firewall proxy, cert download
│   │   ├── security/      # MultiSourceAuthenticationProvider, SecurityConfig,
│   │   │                  # ProxyAuthorizationServer, AstralPrincipal
│   │   └── model/
│   └── tools/             # Installer, InstallerFirewall, InstallerProxy,
│                          # NetworkConfig, Uninstaller
├── src/main/resources/    # application.properties + UI statique héritée
├── frontend/              # Vite + React 18 + PrimeReact
├── fabric/
│   ├── DNS/               # Pi-hole
│   ├── firewall/          # module Java avec 14 entités JPA
│   ├── network-firewall/  # Python
│   ├── samba-ad-dc/       # Samba AD DC (Arch, Debian 13, Fedora)
│   ├── acess-report-system/  # Flask + PostgreSQL
│   └── frontend/          # UI héritée servie par le proxy
├── scripts/               # installateurs et vérificateurs bash
├── etc/astral/            # ad.properties
└── installbase.sh         # installateur de dépendances système
```

## Standards de code

* Suivez le style de code existant (Spring Boot 3.3.5 / Java 21, Python,
  React).
* Utilisez des noms de variables et de méthodes significatifs.
* Écrivez des messages de commit clairs, en portugais, au format conventionnel
  (`type(portée) : description`).
* Gardez les méthodes petites et concentrées sur une seule responsabilité.
* **Commentaire plutôt que code mort.** Ce projet comporte de nombreux
  commentaires expliquant *pourquoi* quelque chose est ainsi. Préservez cette
  pratique.

## 📄 Document vivant

Les changements d'architecture, les décisions et la justification du projet sont
consignés dans [`projectupdates.md`](../../projectupdates.md). Si votre PR change
une décision, mettez ce fichier à jour dans la même PR.

## Licence

En contribuant, vous acceptez que vos contributions soient sous licence GNU
Affero General Public License v3.0 (AGPLv3).

## 🌐 Autres langues

| Langue | Document |
|--------|----------|
| 🇧🇷 Português (Brasil) | [CONTRIBUTING.pt-BR.md](CONTRIBUTING.pt-BR.md) |
| 🇺🇸 English | [CONTRIBUTING.en-US.md](CONTRIBUTING.en-US.md) |
| 🇪🇸 Español | [CONTRIBUTING.es-ES.md](CONTRIBUTING.es-ES.md) |
| 🇫🇷 Français | [CONTRIBUTING.fr-FR.md](CONTRIBUTING.fr-FR.md) |