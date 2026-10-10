# Audit de sécurité du backend GestStock

Date : 2026-10-10 — périmètre : `gestionStock-backend` (Spring Boot 4.1.1, Java 21, PostgreSQL),
code, configuration, dépendances, dépôt Git. Méthode : relecture du code et de la configuration,
tests dynamiques sur l'application démarrée (MockMvc, vrais jetons JWT), interrogation de la base
de vulnérabilités OSV pour les 118 dépendances d'exécution.

Niveaux : **Élevé** (exploitable sans droits particuliers ou fuite de secret), **Moyen**
(exploitable par un utilisateur connecté ou dans des conditions précises), **Faible**
(défense en profondeur), **Info** (hygiène, pas de risque direct).

## Synthèse

| # | Vulnérabilité | Niveau | Statut |
|---|---|---|---|
| 1 | Dépendances Jackson vulnérables (déni de service sur le JSON, route publique `/api/auth/login`) | Élevé | Corrigé |
| 2 | Mot de passe PostgreSQL versionné et poussé (`application-test.yml`) | Élevé | Corrigé dans le code ; **rotation et purge de l'historique à faire** |
| 3 | Tomcat 11.0.24 : avis de sécurité (authentificateurs DIGEST/FORM non utilisés, contrôle d'accès) | Moyen | Corrigé |
| 4 | Mot de passe provisoire non imposé côté serveur (`must_change_password` informatif) | Moyen | **À valider** : nécessite l'écran de changement côté Angular |
| 5 | Pas de limitation de débit par IP ; le verrouillage de compte permet de bloquer un compte connu | Moyen | **Prévu V2** par l'architecture (filtre ou reverse proxy) |
| 6 | IDOR : ventes d'un client visibles tous dépôts confondus | Moyen | Corrigé |
| 7 | Validation du corps avant le contrôle de permission (400 au lieu de 403, audit mal qualifié) | Faible | Corrigé |
| 8 | Jeton signé sans `exp` accepté sans limite de durée | Faible | Corrigé |
| 9 | Affectation de dépôts au-delà de son propre périmètre (rôle personnalisé avec `user:manage`) | Faible | Corrigé |
| 10 | Pas de limite de taille des corps JSON côté application | Faible | Recommandé (reverse proxy) |
| 11 | Mode « liste complète » jusqu'à 5 000 lignes (compatibilité front) | Faible | Recommandé (pagination côté front) |
| 12 | Jokers `%` / `_` non échappés dans la recherche des marques et catégories | Info | Corrigé |
| 13 | Données métier (valeurs en double) dans les journaux de violation d'intégrité | Info | Accepté |
| 14 | Robustesse de la clé JWT contrôlée par la seule longueur | Info | Documenté |
| 15 | Documentation : `forgot-password` annoncé 200 au lieu de 202 | Info | Corrigé |

## Détail

### 1. Dépendances Jackson vulnérables — Élevé

- **Preuve** : OSV signale pour `tools.jackson.core:jackson-databind/jackson-core 3.1.5` et
  `com.fasterxml.jackson.core 2.21.5` : GHSA-cxp5-3px4-pw24 (élevé, complétion quadratique),
  GHSA-q4xh-88c3-wmh7 (élevé, analyse numérique non bornée), GHSA-wv8q-qhhj-9h54 (élevé),
  GHSA-7hhh-6rmp-j9qf (élevé, chaîne non bornée), GHSA-p6pp-m3f8-5c89 (élevé, ReDoS),
  GHSA-gx83-3vf8-gh7j et GHSA-wjgm-6hv5-3cvf (moyens).
- **Impact** : déni de service par un corps JSON forgé, **sans authentification** (le corps de
  `POST /api/auth/login` est désérialisé avant toute vérification).
- **Correction** : Jackson 3.1.7 et 2.21.7 (propriétés `jackson-bom.version`,
  `jackson-2-bom.version` du `pom.xml`, en attendant une version de Spring Boot qui les embarque).

### 2. Mot de passe PostgreSQL versionné — Élevé

- **Preuve** : `src/main/resources/application-test.yml` contient
  `${GESTSTOCK_TEST_DB_PASSWORD:<mot de passe>}` (8 caractères) depuis le commit `0e3486b`,
  présent sur `origin/main` (GitHub).
- **Impact** : le mot de passe du compte `postgres` du poste de développement est lisible par
  quiconque accède au dépôt ; s'il est réutilisé ailleurs (serveur, autre base), compromission.
- **Correction** : valeur retirée du fichier versionné, déplacée dans `config/application-test.yml`
  (ignoré par Git, comme `config/application-dev.yml`). **À faire par le propriétaire** : changer
  le mot de passe du compte `postgres` (il reste dans l'historique Git), puis, si le dépôt est ou
  devient public, purger l'historique (`git filter-repo`) et forcer la mise à jour du dépôt distant.

### 3. Tomcat 11.0.24 — Moyen

- **Preuve** : GHSA-9xv2-5v5q-p794, GHSA-gcx9-497g-6cp6, GHSA-h3x4-894j-xpx5 (critiques selon OSV),
  corrigés en 11.0.25.
- **Impact** : deux avis portent sur les authentificateurs DIGEST et FORM de Tomcat, **non utilisés**
  (authentification JWT par Spring Security) ; le troisième (contrôle d'accès) justifie la mise à jour.
- **Correction** : Tomcat 11.0.26 (propriété `tomcat.version`).

### 4. Mot de passe provisoire non imposé — Moyen (à valider)

- **Preuve** : `must_change_password` n'est lu nulle part pour restreindre l'accès ; un compte créé
  par un administrateur (mot de passe provisoire) utilise toute l'API sans le changer.
- **Impact** : un mot de passe transmis par l'administrateur (souvent par message) reste valable
  indéfiniment.
- **Correction recommandée** : refuser toute route sauf `/api/auth/me`, `/api/auth/change-password`
  et `/api/auth/logout` (403 `PASSWORD_CHANGE_REQUIRED`) tant que le mot de passe est provisoire.
  **Non appliquée** : le front Angular n'a pas d'écran de changement de mot de passe ; l'appliquer
  aujourd'hui bloquerait tout nouveau compte. À livrer avec cet écran (exigence de l'architecture :
  « mot de passe admin initial imposé à changer »).

### 5. Pas de limitation de débit par IP — Moyen (prévu V2)

- **Preuve** : aucun filtre de débit ; seul le verrouillage de compte (5 échecs, 15 min) protège
  `/api/auth/login`.
- **Impact** : force brute répartie sur de nombreux comptes ; un attaquant qui connaît un
  identifiant peut le bloquer en boucle (déni de service ciblé).
- **Correction recommandée** : l'architecture prévoit en V1 le verrouillage de compte (en place)
  et en V2 une limitation par IP. En production : limiter `/api/auth/*` au reverse proxy (ex.
  `limit_req` nginx, 10 requêtes/minute/IP), ou filtre applicatif en V2.

### 6. IDOR : ventes d'un client tous dépôts — Moyen

- **Preuve** : `GET /api/customers/{customerId}/sales` appelle `search(…, null, …)` : aucun filtre
  de dépôt, alors que toutes les autres lectures de ventes sont limitées aux dépôts de l'utilisateur.
- **Impact** : un caissier affecté à un magasin lit les ventes (montants, vendeurs, crédits) des
  autres magasins.
- **Correction** : liste limitée aux dépôts accessibles de l'utilisateur (tous avec `warehouse:all`).

### 7. Validation avant permission — Faible

- **Preuve** : test `DirectApiAccessIT` : un utilisateur connecté sans aucun rôle reçoit **403 sur
  toutes les lectures**, mais **400** sur 42 routes d'écriture appelées avec `{}` (la validation
  `@Valid` du corps précède `@PreAuthorize`). Aucune route n'exécute de traitement (ni 2xx, ni
  404/409/422 métier).
- **Impact** : pas de contournement, mais un utilisateur non autorisé apprend les règles de
  validation ; le journal d'audit trace `VALIDATION_FAILED` au lieu d'une tentative non autorisée.
- **Correction** : contrôle des permissions **avant** la lecture du corps (intercepteur appliquant
  les mêmes annotations `@RequiresPermission` / `@PreAuthorize`, qui restent en place).

### 8. Jeton sans `exp` accepté — Faible

- **Preuve** : un JWT signé avec la clé du serveur mais sans `exp` donne 200 sur `/api/auth/me`
  (`JwtTimestampValidator` n'exige pas la présence du claim).
- **Impact** : nécessite la clé de signature ; défense en profondeur (un jeton éternel ne doit
  jamais être accepté, même en cas de bogue d'émission).
- **Correction** : `exp` et `iat` obligatoires dans la validation des jetons.

### 9. Affectation de dépôts hors périmètre — Faible

- **Preuve** : `UserService.applyWarehouses` accepte n'importe quel dépôt actif ; seule l'escalade
  de permissions est contrôlée (`requireGrantable`).
- **Impact** : nul avec les rôles livrés (`user:manage` = ADMIN, qui voit tous les dépôts) ; un rôle
  personnalisé `user:manage` sans `warehouse:all` pourrait donner accès à des dépôts qu'il ne voit pas.
- **Correction** : chaque dépôt affecté doit être accessible à l'auteur.

### 10. Taille des corps JSON — Faible (recommandé)

Les listes sont bornées après lecture (`@Size`), mais un corps de plusieurs centaines de Mo est lu
avant. En production : `client_max_body_size 1m` au reverse proxy.

### 11. Listes complètes jusqu'à 5 000 lignes — Faible (recommandé)

Compatibilité avec le front actuel (listes sans `page`/`size`). À supprimer quand le front paginera
côté serveur ; les rapports sont déjà toujours paginés (200 lignes au plus).

### 12. Jokers non échappés — Info

`BrandService` / `CategoryService` : `like '%texte%'` sans échappement de `%` et `_`. Pas d'injection
SQL (paramètre lié), seulement des résultats plus larges. Corrigé comme dans les autres modules.

### 13 à 15 — Info

- Les violations d'intégrité sont journalisées avec le message PostgreSQL (`Key (email)=(…)`) :
  donnée métier dans les journaux, sans secret. Accepté.
- La clé JWT est contrôlée par sa longueur (≥ 32 octets) : générer avec
  `openssl rand -base64 48` (documenté au README).
- `POST /api/auth/forgot-password` renvoie 202 mais était documenté 200 : corrigé.

## Contrôles conformes (aucune vulnérabilité)

| Point | Constat |
|---|---|
| JWT | HS256 imposé (pas d'`alg: none`), émetteur vérifié, durée 15 min, tolérance d'horloge 30 s, jeton lu dans l'en-tête seulement (jamais dans l'URL), clé ≥ 32 octets obligatoire hors dev ; compte relu à chaque requête (désactivé, verrouillé, mot de passe changé → 401). |
| Refresh tokens | 256 bits aléatoires, stockés hachés (SHA-256), rotation à chaque usage, réutilisation détectée → famille révoquée, 7 jours. |
| Mots de passe | bcrypt coût 12 (préfixé, migrable), politique (8 caractères, lettre + chiffre, ≤ 72 octets, ≠ identifiant), jamais journalisés ni renvoyés ni tracés (masquage dans l'audit). |
| Énumération | Réponse et temps identiques pour un compte inconnu, un mauvais mot de passe ou un compte sans mot de passe ; « désactivé » révélé seulement après un mot de passe correct ; `forgot-password` toujours 202. |
| CSRF | Désactivé à raison : API sans état, authentification par en-tête `Authorization`, aucun cookie. |
| CORS | Origines explicites (obligatoires en prod), `allowCredentials=false`, méthodes et en-têtes listés. |
| Injection SQL | Toutes les requêtes à paramètres liés ; fragments dynamiques constants ; tris par liste blanche ; aucune concaténation de saisie. |
| Mass assignment | DTO dédiés (jamais d'entité JPA liée) ; prix, coûts, totaux, auteur, dates calculés par le serveur ; plafond de crédit sous `customer:credit` ; rôles sous contrôle d'escalade. |
| Contrôle d'accès | `EndpointSecurityCoverageIT` : toute route publique ou protégée par une permission ; `DirectApiAccessIT` : un utilisateur sans rôle n'obtient rien sur aucune des 130 routes ; `RolePermissionMatrixIT` : matrice des rôles avec vrais jetons ; ressources hors dépôt → 404 (sans révéler leur existence). |
| Endpoints publics | `login`, `refresh`, `logout`, `forgot-password`, `health` ; Swagger et `/v3/api-docs` désactivés en prod ; actuator limité à `health`, sans détails en prod. |
| Erreurs | Format `ApiError` ; ni pile, ni SQL, ni message d'exception renvoyés (`include-stacktrace: never`) ; 500 générique avec `traceId`. |
| Journaux | Ni mot de passe, ni jeton ; `X-Trace-Id` client validé (`[A-Za-z0-9._-]{8,64}`) contre l'injection dans les journaux. |
| En-têtes | En-têtes de sécurité par défaut de Spring Security (`X-Content-Type-Options`, `X-Frame-Options: DENY`, `Cache-Control: no-store`, HSTS en HTTPS). |
| Secrets / environnement | Prod sans valeur par défaut (base, clé JWT, CORS) : démarrage refusé si une variable manque ; aucun compte par défaut (`AdminBootstrap`). |
| Uploads | Aucun envoi de fichier dans l'API. |
| Pagination | `size` ≤ 200 (400 au-delà), tri par liste blanche ; export d'audit plafonné à 50 000 lignes et réservé à `audit:read`. |

## Vérifications automatisées ajoutées

- `security/DirectApiAccessIT` : chaque route appelée directement (vrai jeton, utilisateur sans
  rôle) → 403 partout, y compris en écriture avec un corps invalide ; jeton sans `exp` refusé.
- Tests des corrections : IDOR des ventes client, affectation de dépôts hors périmètre.
