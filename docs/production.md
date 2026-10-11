# Mise en production de GestStock

Ce document décrit l'architecture cible, la configuration du backend et du front, et la checklist de mise en production.

Il complète [security-audit.md](security-audit.md), qui recense les risques connus.

## Architecture cible

```
Navigateur ──HTTPS──▶ Reverse proxy public (Nginx + Let's Encrypt, deploy/reverse-proxy/geststock.conf)
                            │  HTTP local
                            ▼
                    Conteneur front (Nginx, port 80 → 8088)
                     ├── /        application Angular (fichiers statiques)
                     └── /api/    relayé au backend (même origine : pas de CORS)
                            │
                            ▼
                    Conteneur backend (Spring Boot, profil prod, port 8080, non publié)
                            │
                            ▼
                    PostgreSQL 17 (non publié ; base managée ou conteneur avec volume sauvegardé)
```

## Backend : configuration

### Profil

- **Toujours démarrer avec `SPRING_PROFILES_ACTIVE=prod`.** L'image Docker le fixe.
- Sans profil, l'application démarre en profil `dev` :
  - elle charge les données de démonstration ;
  - elle accepte une clé JWT éphémère ;
  - elle autorise CORS sur `localhost`.
- Un avertissement `Profil DEV actif` apparaît alors dans les journaux.

### Variables d'environnement

Les variables sans valeur par défaut sont obligatoires : sans elles, le démarrage échoue.

| Variable | Obligatoire | Rôle |
|---|---|---|
| `GESTSTOCK_DB_URL` | oui | `jdbc:postgresql://hôte:5432/geststock` (ajouter `?sslmode=require` si la base est distante) |
| `GESTSTOCK_DB_USERNAME` / `GESTSTOCK_DB_PASSWORD` | oui | Rôle dédié, propriétaire du schéma, **non superutilisateur** |
| `GESTSTOCK_JWT_SECRET` | oui | Clé HMAC de 32 octets minimum (`openssl rand -base64 48`). La changer déconnecte tout le monde. |
| `GESTSTOCK_CORS_ALLOWED_ORIGINS` | oui | URL publique du front (`https://…`). Le joker `*` et `http://` sont refusés au démarrage. |
| `GESTSTOCK_ADMIN_USERNAME` / `GESTSTOCK_ADMIN_PASSWORD` | 1er démarrage | Administrateur créé s'il n'en existe aucun actif (mot de passe de 12 caractères minimum). Retirer ces variables ensuite. |
| `GESTSTOCK_DB_POOL_SIZE` | non (20) | Connexions maximum du pool Hikari |
| `GESTSTOCK_DB_POOL_MIN_IDLE` | non (2) | Connexions gardées ouvertes |
| `GESTSTOCK_DB_CONNECTION_TIMEOUT_MS` | non (10000) | Attente d'une connexion libre |
| `GESTSTOCK_DB_SOCKET_TIMEOUT_S` | non (120) | Requête SQL bloquée au-delà : connexion coupée |
| `GESTSTOCK_DB_LEAK_DETECTION_MS` | non (60000) | Alerte si une connexion n'est pas rendue au pool |
| `GESTSTOCK_TX_TIMEOUT` | non (60s) | Durée maximale d'une transaction |
| `GESTSTOCK_HTTP_THREADS_MAX` | non (200) | Threads Tomcat |
| `GESTSTOCK_ACCESS_TOKEN_TTL` / `GESTSTOCK_REFRESH_TOKEN_TTL` | non (15m / 7d) | Durée de vie des jetons |
| `GESTSTOCK_LOG_FORMAT` | non (ecs) | Journaux JSON : `ecs`, `logstash` ou `gelf` |
| `GESTSTOCK_DASHBOARD_TIME_ZONE` | non (Africa/Abidjan) | Fuseau de l'entreprise : « aujourd'hui », heure des rendez-vous |
| `GESTSTOCK_SALES_MAX_DISCOUNT_PERCENT` | non (20) | Remise maximale par ligne de vente |

**Les secrets ne sont jamais écrits dans Git.** Ils sont fournis par :
- le gestionnaire de secrets de la plateforme ;
- ou un fichier `.env` sur le serveur, lisible par root seulement. `.env` et `deploy/.env` sont ignorés par Git.

### Contrôles au démarrage (profil prod)

| Contrôle | Classe |
|---|---|
| Secret JWT présent et d'au moins 32 octets | `JwtConfig` |
| CORS : au moins une origine, uniquement `https://`, jamais `*` | `ProductionSafetyCheck` |
| Mot de passe de l'administrateur initial : 12 caractères minimum | `ProductionSafetyCheck` |
| Flyway `clean` interdit | `ProductionSafetyCheck` |
| Variables de base de données présentes | `application-prod.yml`, sans valeurs par défaut |

Une recette locale en `http://` est possible avec `GESTSTOCK_PRODUCTION_ALLOW_HTTP_ORIGINS=true`. Ce réglage est réservé à `deploy/docker-compose.local.yml` et ne doit jamais être utilisé sur un serveur.

### PostgreSQL et Flyway

- **Rôle applicatif :** propriétaire de la base, sans droits de superutilisateur. Script : [`scripts/create-local-db.sql`](../scripts/create-local-db.sql).
- **Migrations :** Flyway les applique au démarrage (`V001`…`V028`, en ajout seul). `validate-on-migrate` vérifie les sommes de contrôle et `clean` est désactivé.
  - **Ne jamais modifier une migration déjà appliquée** : créer une nouvelle version.
  - Les scripts de retour arrière se trouvent dans `db/rollback/U0xx__*.sql`. Ils sont manuels et détruisent des données.
- **Données de démonstration :** `db/seed/dev` n'est chargé qu'en profil `dev`. La production démarre vide, avec seulement les données de référence des migrations (rôles, permissions, dépôt principal, catégories).
- **Sauvegardes :**
  - `pg_dump` quotidien chiffré, conservé hors du serveur ;
  - restauration testée au moins une fois avant l'ouverture.
- **Fuseau :** la base stocke tout en UTC (`hibernate.jdbc.time_zone=UTC`).

### Pool de connexions et délais

| Paramètre | Valeur | Rôle |
|---|---|---|
| Pool Hikari | 20 connexions maximum en prod, 2 gardées ouvertes | Taille du pool |
| `connection-timeout` | 10 s | Attente d'une connexion libre |
| `idle-timeout` | 10 min | Fermeture d'une connexion inactive |
| `max-lifetime` | 30 min | Renouvellement des connexions |
| `keepalive` | 5 min | Détection d'une base redémarrée |
| `socketTimeout` | 120 s | Coupure d'une requête SQL bloquée |
| Détection de fuite | 60 s | Alerte si une connexion n'est pas rendue au pool |
| Transactions | 60 s maximum | Durée d'une transaction |
| Tomcat | connexion inactive coupée après 20 s | Connexions HTTP |
| Arrêt gracieux | 30 s | Les requêtes en cours se terminent avant l'arrêt |

Règle de dimensionnement : la somme des pools de toutes les instances doit rester sous `max_connections` de PostgreSQL, moins une marge de 10 connexions environ.

### Santé et supervision

| Endpoint | Public | Usage |
|---|---|---|
| `/actuator/health` | oui, sans détail | État global, base de données comprise |
| `/actuator/health/liveness` | oui | Le processus répond. Sinon, redémarrer le conteneur (healthcheck Docker). |
| `/actuator/health/readiness` | oui | Prêt à recevoir du trafic (base joignable) |
| autres `/actuator/*` | non exposés | — |

Le reverse proxy public renvoie 404 sur `/actuator/` : les sondes s'interrogent depuis le réseau interne.

Supervision recommandée :
- une sonde externe sur `https://<domaine>/api/health` ou sur la readiness en interne ;
- une alerte sur les journaux de niveau `ERROR` ;
- une alerte sur le message Hikari `Connection leak detection` ;
- une alerte sur l'espace disque de PostgreSQL.

### Journaux

- **Format :** JSON au format ECS sur la sortie standard, collecté par Docker ou la plateforme.
- **Corrélation :** chaque ligne porte le `traceId` de la requête (en-tête `X-Trace-Id`). Ce même identifiant figure dans le corps des erreurs renvoyées au front, ce qui permet de relier un message utilisateur à son entrée de journal.
- **Contenu :** jamais de requêtes SQL, de mots de passe ou de jetons. Les DTO sensibles masquent leur `toString`.
- **Niveaux :** `INFO`, et `WARN` pour Hibernate SQL.

### Gestion des erreurs

- **Format des erreurs :** RFC 7807 étendu, avec `code`, `message`, `traceId` et `path`. Jamais de pile d'appels ni de message technique : `server.error.include-*: never`.
- **Statuts :**
  - 401 : non authentifié ou jeton invalide ;
  - 403 : permission manquante, vérifiée avant la validation du corps ;
  - 404, 409, 422 : erreurs métier ;
  - 500 : erreur interne avec `traceId`.

### Sécurité (rappel)

**Authentification**
- JWT signé HS256 : émetteur, `exp` et `iat` obligatoires, jeton lu uniquement dans l'en-tête `Authorization`.
- Refresh tokens à usage unique : une réutilisation révoque toute la session.
- Verrouillage du compte après 5 échecs de connexion, pendant 15 min.

**Requêtes HTTP**
- Session sans état, CSRF désactivé : pas de cookie d'authentification.
- CORS limité à l'origine du front.

**Exposition**
- Documentation OpenAPI et Swagger désactivées en prod.
- En-têtes de sécurité :
  - posés par Spring Security et par le Nginx du front (CSP, `X-Frame-Options`, `nosniff`) ;
  - HSTS posé par le reverse proxy public.

**Points ouverts** (voir `security-audit.md`) :
- limitation de débit par IP : à faire au niveau du reverse proxy, une zone `limit_req` est fournie sur `/api/auth/login` ;
- changement de mot de passe forcé au premier login, faute d'écran front.

## Front : configuration

- **Build de production :** `npx ng build --configuration production`. Il utilise :
  - `environment.prod.ts` : `apiUrl: '/api'`, URL relative, donc même origine que le front ;
  - `mock-providers.prod.ts` : aucun backend simulé. Le mock et ses données ne sont pas dans le bundle, ce que la CI vérifie.
- **Session :**
  - 401 : renouvellement du jeton une seule fois, en parallèle pour toutes les requêtes en attente ;
  - si le renouvellement est refusé, la session se ferme et l'utilisateur revient à l'écran de connexion.
- **Erreurs réseau et serveur :**
  - réseau coupé : « Serveur injoignable » ;
  - 502, 503 ou 504 : « Service momentanément indisponible » ;
  - 403 : message du backend ;
  - 500 : message accompagné de la référence `traceId`.
- **Conteneur :**
  - le `Dockerfile` du dépôt front construit l'application, puis Nginx la sert ;
  - Nginx relaie `/api/` vers `BACKEND_URL`, ajoute les en-têtes de sécurité et la CSP ;
  - les fichiers versionnés sont mis en cache longtemps, `index.html` ne l'est jamais.

## Infrastructure fournie

| Fichier | Rôle |
|---|---|
| `Dockerfile` (backend) | Image multi-étapes, JRE 21, utilisateur non root, profil `prod`, healthcheck sur la liveness, couches Spring Boot |
| `Dockerfile` + `deploy/nginx.conf.template` (front) | Build Angular, puis Nginx (application + relais `/api`) |
| `docker-compose.yml` (backend) | **Développement uniquement** : PostgreSQL local |
| `deploy/docker-compose.local.yml` | Pile complète configurée comme la production (PostgreSQL non publié + backend + front sur http://localhost:8088). Secrets dans `deploy/.env`, à copier depuis `.env.example`. |
| `deploy/reverse-proxy/geststock.conf` | Nginx public : redirection HTTP vers HTTPS, Let's Encrypt, HSTS, `/actuator` bloqué, limitation de débit sur le login |
| `.github/workflows/ci.yml` (2 dépôts) | Tests et build à chaque push ou PR ; image publiée sur GHCR à chaque tag `v*` |

**Déploiement continu (proposition) :**
1. Le tag `vX.Y.Z` déclenche la CI, puis la publication des images `ghcr.io/<compte>/geststock-backend` et `geststock-frontend`.
2. Sur le serveur, lancer `docker compose pull && docker compose up -d` avec un compose de production. C'est le même que le compose local, avec les images publiées au lieu de `build:` et sans la variable `GESTSTOCK_PRODUCTION_ALLOW_HTTP_ORIGINS`.
3. Pour automatiser cette étape, ajouter un job SSH dans la CI. Il demande un secret `DEPLOY_SSH_KEY` stocké dans GitHub, jamais dans le dépôt.

## Checklist de mise en production

### Avant le premier déploiement

**Infrastructure**
- [ ] Serveur à jour, pare-feu limité aux ports 22, 80 et 443 ; PostgreSQL et le backend non joignables depuis Internet.
- [ ] Nom de domaine et certificat HTTPS (Let's Encrypt), redirection HTTP vers HTTPS, HSTS actif.

**Base de données**
- [ ] Base PostgreSQL 17 dédiée, rôle applicatif non superutilisateur, mot de passe fort.
- [ ] Sauvegarde automatique quotidienne testée par une restauration.

**Secrets et configuration**
- [ ] `GESTSTOCK_JWT_SECRET` généré aléatoirement (48 octets), stocké hors Git.
- [ ] `GESTSTOCK_CORS_ALLOWED_ORIGINS` = `https://<domaine>`.
- [ ] `SPRING_PROFILES_ACTIVE=prod` (fixé par l'image).
- [ ] Administrateur initial : `GESTSTOCK_ADMIN_*` définis pour le premier démarrage. Après connexion, changer le mot de passe et retirer ces variables.
- [ ] `GESTSTOCK_DASHBOARD_TIME_ZONE` = fuseau réel de l'entreprise.
- [ ] Aucun secret dans les dépôts : `git grep` sur les mots de passe et les clés, `.env` ignoré par Git.
- [ ] Ne jamais réutiliser les mots de passe d'exemple : `Optique2026!` apparaît dans la doc OpenAPI et dans les tests.

**Vérification de la livraison**
- [ ] CI au vert sur le commit livré : tests backend et front, build de production.

### Au déploiement

- [ ] Images taguées `vX.Y.Z` (jamais `latest` seul en production).
- [ ] Démarrage : les journaux montrent `Contrôles de production passés`, les migrations Flyway appliquées et aucun `ERROR`.
- [ ] `/actuator/health`, `/actuator/health/liveness` et `/actuator/health/readiness` répondent `UP` en interne.
- [ ] `/actuator/*` renvoie 404 depuis Internet ; `/v3/api-docs` et `/swagger-ui.html` renvoient 401 ou 404.

### Recette après déploiement

- [ ] Connexion et déconnexion de l'administrateur ; un mauvais mot de passe renvoie 401.
- [ ] Création d'un caissier : il reçoit 403 sur Utilisateurs, Audit, Rapports et Dépenses.
- [ ] Parcours métier :
  - catégorie, puis produit, puis entrée de stock ;
  - ouverture de caisse, vente comptant, vente à crédit puis règlement ;
  - dépense en espèces puis annulation ;
  - rendez-vous ;
  - clôture de caisse sans écart.
- [ ] Le journal d'audit contient ces actions ; les tableaux de bord affichent les chiffres.
- [ ] Le front fonctionne en navigation privée : rafraîchissement d'une route profonde (`/clients`), aucune erreur CSP dans la console.

### Exploitation

- [ ] Supervision : sonde HTTPS, alertes sur les erreurs et le disque, rotation des journaux Docker (`max-size`).
- [ ] Procédure de retour arrière : image précédente plus restauration de la base. Ne jamais appliquer un `U0xx` en production sans sauvegarde.
- [ ] Les comptes de test de dev (`caisse.test`, `gestion.test`) n'existent pas en production.
