# GestStock — Backend

API REST de GestStock (gestion de stock et gestion commerciale), destinée au front Angular
situé à la racine du dépôt.

| Brique | Version |
|---|---|
| Java | 21 |
| Spring Boot | 4.1.1 (Spring Web MVC, Data JPA / Hibernate 7, Security, Validation, Actuator) |
| Base de données | PostgreSQL (testé sur 17 et 18) |
| Migrations | Flyway 12 |
| Documentation API | springdoc-openapi 3 (Swagger UI) |
| Tests | JUnit 5, Mockito, Spring Boot Test, Testcontainers |

État actuel : **socle technique, persistance, utilisateurs (`/api/users`) et authentification
JWT (`/api/auth`)**. Sans jeton valide, toute route protégée répond 401.

## Installation

Prérequis : **JDK 21** et une base **PostgreSQL** (locale ou Docker). Maven n'est pas
nécessaire : le wrapper `mvnw` le télécharge.

```bash
cd backend
./mvnw -q verify -DskipITs     # compile et lance les tests sans base de données
```

Sous Windows (PowerShell ou cmd) : `mvnw.cmd` au lieu de `./mvnw`.

## Configuration PostgreSQL

### Option A — PostgreSQL installé localement

Le script `scripts/create-local-db.sql` crée le rôle applicatif `geststock` et les bases
`geststock` (développement) et `geststock_test` (tests). Il est idempotent.

```bash
psql -h localhost -p 5432 -U postgres -v app_password='MOT_DE_PASSE_CHOISI' -f scripts/create-local-db.sql
```

### Option B — Docker

```bash
export GESTSTOCK_DB_PASSWORD='MOT_DE_PASSE_CHOISI'
docker compose up -d
```

Dans les deux cas, **les tables sont créées par Flyway** au démarrage de l'application :
il n'y a aucun script de schéma à lancer à la main.

## Lancement

```bash
# PowerShell : $env:GESTSTOCK_DB_PASSWORD = 'MOT_DE_PASSE_CHOISI'
export GESTSTOCK_DB_PASSWORD='MOT_DE_PASSE_CHOISI'
./mvnw spring-boot:run
```

| URL | Rôle |
|---|---|
| http://localhost:8080/api/health | État de l'API et de la base (`UP` / `DOWN`) |
| http://localhost:8080/swagger-ui.html | Documentation interactive (profils `dev` et `test`) |
| http://localhost:8080/v3/api-docs | Contrat OpenAPI (JSON) |
| http://localhost:8080/actuator/health | Sonde de supervision |

Jar exécutable : `./mvnw package -DskipTests` puis `java -jar target/geststock-backend-0.0.1-SNAPSHOT.jar`.

## Tests

| Commande | Contenu |
|---|---|
| `./mvnw test` | Tests unitaires et web (`*Test`), sans base de données |
| `./mvnw verify` | + tests d'intégration (`*IT`) sur PostgreSQL : migrations, conventions de schéma, accès JPA, rollback, démarrage complet |

Les tests d'intégration choisissent leur base automatiquement :

- **Docker disponible** : conteneur PostgreSQL jetable (Testcontainers), rien à configurer ;
- **sinon** : base externe `geststock_test` (option A ci-dessus), avec le mot de passe fourni par variable d'environnement.

```bash
# Sans Docker
export GESTSTOCK_TEST_TESTCONTAINERS=false
export GESTSTOCK_TEST_DB_PASSWORD='MOT_DE_PASSE_CHOISI'
./mvnw verify
```

> La base `geststock_test` doit être **réservée aux tests**.

## Profils

| Profil | Activation | Particularités |
|---|---|---|
| `dev` | par défaut (aucun profil fourni) | Données de démonstration (`db/seed/dev`), Swagger actif, CORS `http://localhost:4200`, logs `DEBUG` |
| `test` | tests d'intégration (`@ActiveProfiles("test")`) | Base `geststock_test` ou Testcontainers |
| `prod` | `SPRING_PROFILES_ACTIVE=prod` | Aucune valeur par défaut pour la base et le CORS : démarrage refusé si une variable manque ; Swagger désactivé ; en-têtes de proxy pris en compte |

## Variables d'environnement

| Variable | Défaut | Obligatoire en `prod` | Rôle |
|---|---|---|---|
| `GESTSTOCK_DB_URL` | `jdbc:postgresql://localhost:5432/geststock` | oui | URL JDBC |
| `GESTSTOCK_DB_USERNAME` | `geststock` | oui | Utilisateur PostgreSQL |
| `GESTSTOCK_DB_PASSWORD` | *(vide)* | oui | Mot de passe PostgreSQL |
| `GESTSTOCK_DB_POOL_SIZE` | `10` (`20` en prod) | non | Taille du pool de connexions |
| `GESTSTOCK_SERVER_PORT` | `8080` | non | Port HTTP |
| `GESTSTOCK_CORS_ALLOWED_ORIGINS` | `http://localhost:4200` en dev, aucune sinon | oui | Origines autorisées, séparées par des virgules |
| `GESTSTOCK_JWT_SECRET` | aucune (clé aléatoire en dev) | oui | Clé de signature des jetons, **32 octets minimum** |
| `GESTSTOCK_ACCESS_TOKEN_TTL` | `15m` | non | Durée de vie d'un jeton d'accès |
| `GESTSTOCK_REFRESH_TOKEN_TTL` | `7d` | non | Durée de vie d'un refresh token |
| `GESTSTOCK_ADMIN_USERNAME` | *(vide)* | au 1er démarrage | Administrateur créé s'il n'en existe aucun |
| `GESTSTOCK_ADMIN_PASSWORD` | *(vide)* | au 1er démarrage | Son mot de passe (temporaire) |
| `SPRING_PROFILES_ACTIVE` | `dev` | — | Profil actif |
| `GESTSTOCK_TEST_DB_URL` | `jdbc:postgresql://localhost:5432/geststock_test` | — | Base des tests sans Docker |
| `GESTSTOCK_TEST_DB_USERNAME` | `geststock` | — | Utilisateur de la base de test |
| `GESTSTOCK_TEST_DB_PASSWORD` | *(vide)* | — | Mot de passe de la base de test |
| `GESTSTOCK_TEST_TESTCONTAINERS` | `auto` | — | `auto`, `true` (Docker obligatoire) ou `false` (base externe) |

Aucun secret n'est versionné : les mots de passe passent uniquement par ces variables.

## Authentification

| Endpoint | Accès | Rôle |
|---|---|---|
| `POST /api/auth/login` | public | `{username, password}` → `{accessToken, refreshToken, expiresIn, tokenType, user}` |
| `POST /api/auth/refresh` | public | `{refreshToken}` → nouvelle session ; l'ancien jeton est révoqué |
| `POST /api/auth/logout` | public | `{refreshToken}` → 204, session fermée |
| `GET /api/auth/me` | authentifié | Profil et permissions effectives |
| `POST /api/auth/change-password` | authentifié | Ferme toutes les sessions et en ouvre une nouvelle |
| `POST /api/auth/forgot-password` | public | Réponse identique que le compte existe ou non |

Principes :

- **le serveur décide** : identité lue dans un JWT signé (HS256) ; compte, rôles et permissions relus en base à chaque requête (désactivation et changement de rôle immédiats) ;
- **refus par défaut** : aucune permission sans rôle ; chaque endpoint métier exige une permission (`@PreAuthorize`), vérifié par `EndpointSecurityCoverageIT` ;
- **aucun compte par défaut** : l'administrateur initial n'existe que si `GESTSTOCK_ADMIN_USERNAME` et `GESTSTOCK_ADMIN_PASSWORD` sont fournis ;
- verrouillage temporaire après 5 échecs (15 min), messages d'échec génériques ;
- refresh tokens opaques, stockés hachés, à usage unique (rotation) ; leur réutilisation révoque toute la session ;
- changement ou réinitialisation de mot de passe : tous les jetons antérieurs sont refusés.

## Catalogue

| Ressource | Lecture | Écriture | Particularités |
|---|---|---|---|
| `/api/categories` | `category:read` | `category:manage` | 5 catégories optiques livrées par migration |
| `/api/brands` | `brand:read` | `brand:manage` | — |
| `/api/products` | `product:read` | `product:create`, `product:update`, `product:delete` | SKU unique (casse ignorée), code-barres EAN unique, unité (pièce, paire, boîte, flacon) |

- Listes : `q` (recherche), `status=ACTIVE|INACTIVE|ALL` (actifs par défaut), `page`, `size`, `sort` ; produits filtrables par `categoryId`, `brandId`, `supplierId`.
- `DELETE` désactive (jamais d'effacement) ; `PATCH /{id}/active {"active": true}` réactive.
- **Le produit ne porte aucun stock.** `stockQuantity` est calculé à la lecture par le module stock (0 tant qu'il n'existe pas) ; s'il est envoyé à la création ou à la modification, il est ignoré.

## Dépôts

| Endpoint | Permission | Rôle |
|---|---|---|
| `GET /api/warehouses` | `warehouse:read` | Dépôts accessibles (`{items, total}`, contrat du front) ; filtres `q`, `type`, `status`, pagination |
| `GET /api/warehouses/current` | `warehouse:read` | Dépôt retenu par le serveur pour l'en-tête `X-Warehouse-Id` |
| `GET /api/warehouses/{id}` | `warehouse:read` | 404 si hors périmètre |
| `POST /api/warehouses` | `warehouse:manage` | `{"name"}` suffit (front) : code généré `MAG-nn` / `DEP-nn`, type `STORE` |
| `PUT /api/warehouses/{id}` | `warehouse:manage` | Code, nom (uniques), type, adresse, téléphone |
| `PATCH /api/warehouses/{id}/active`, `DELETE` | `warehouse:manage` | Désactivation, jamais d'effacement |
| `POST /api/warehouses/{id}/main` | `warehouse:manage` | Change le dépôt principal |

- **Dépôt principal** (V012) : un seul (index unique partiel), toujours actif ; `MAG-01` à l'installation. Il ne peut pas être désactivé (`409 MAIN_WAREHOUSE_REQUIRED`) avant d'en désigner un autre.
- **Périmètre** (calculé par le serveur, relu à chaque requête) : `warehouse:all` → tous les dépôts ; sinon dépôts attribués + dépôt par défaut de l'utilisateur ; utilisateur sans affectation → dépôt principal. `warehouse:manage` voit tous les dépôts dans `/api/warehouses`.
- **Dépôt courant** : l'en-tête `X-Warehouse-Id` est un *choix* parmi les dépôts autorisés. Dépôt actif hors périmètre → `403 WAREHOUSE_ACCESS_DENIED` (jamais de repli silencieux). En-tête absent, inconnu, désactivé ou ancien identifiant du mock (`wh_1`) → dépôt par défaut, puis premier dépôt attribué, puis dépôt principal. Le dépôt retenu est renvoyé dans l'en-tête de réponse `X-Warehouse-Id`.
- **Modules futurs** (stock, ventes, caisse, inventaire) : déclarer `@CurrentWarehouse WarehouseContext warehouse` dans le contrôleur et rattacher la pièce à `warehouse.id()` ; pour un dépôt désigné dans le corps (destination d'un transfert), appeler `WarehouseAccess.requireAccessible(id)`.

## Stock

**Source de vérité unique : `stock_level`** (une ligne par dépôt × produit). Le produit ne porte aucune quantité ; `stockQuantity` des produits est lu dans `stock_level` pour le dépôt courant.

**Point d'écriture unique : `StockService.applyMovements(...)`** (transactionnel) :
1. contrôle du sens de la quantité selon le type, du dépôt (actif) et du produit ;
2. création à zéro des niveaux absents puis `SELECT … FOR UPDATE` dans l'ordre (dépôt, produit) : pas de survente concurrente, pas d'interblocage ;
3. refus si le stock deviendrait négatif (`422 STOCK_INSUFFICIENT`), sinon écriture du mouvement (auteur = utilisateur connecté, solde après mouvement, coût unitaire) puis du niveau.
Tout ou rien : un refus annule l'ensemble, et un module appelant (vente, achat…) partage la transaction.

| Type | Sens | Usage |
|---|---|---|
| `OPENING`, `RECEIPT`, `CUSTOMER_RETURN`, `SALE_CANCELLATION`, `TRANSFER_IN` | + | reprise, réception, retour client, annulation de vente, arrivée de transfert |
| `SALE`, `SUPPLIER_RETURN`, `LOSS`, `TRANSFER_OUT` | − | vente, retour fournisseur, perte, départ de transfert |
| `ADJUSTMENT`, `INVENTORY`, `REVERSAL` | ± | ajustement, écart d'inventaire (`applyInventoryCounts`, calculé sous verrou), annulation d'un mouvement manuel |

| Endpoint | Permission |
|---|---|
| `GET /api/stock/movements` (`from`, `to`, `productId`, `type`, `allWarehouses`) | `stock:read` (+ `warehouse:all` pour `allWarehouses`) |
| `POST /api/stock/movements` `{productId, quantity signée, reason: SUPPLY\|SALE\|LOSS\|ADJUSTMENT, note}` | `stock:adjust` |
| `POST /api/stock/movements/{id}/reversal` | `stock:adjust` |
| `GET /api/stock/levels` | `stock:read` |
| `POST /api/warehouses/transfer` (contrat front, réponse `{ok: true, …}`) | `stock:transfer` + accès aux deux dépôts |

**Garde-fous PostgreSQL** (V013) : `stock_movement` en ajout seul ; une ligne de `stock_level` naît à zéro, ne peut être supprimée et ne varie que si elle désigne un nouveau mouvement du même couple qui explique exactement la variation (`fn_stock_level_guard`) ; `CHECK quantity >= 0`. Une écriture SQL directe est refusée par la base.

Pas de stock négatif : aucune règle métier ne l'autorise aujourd'hui (l'introduire exigerait de modifier `StockService` et la contrainte `ck_stock_level_quantity`).

## Rôles et permissions (RBAC)

Modèle : **utilisateur → rôles → permissions** `domaine:action`. Les permissions sont calculées
par le serveur à chaque requête ; un endpoint les exige avec
`@RequiresPermission(Permissions.PRODUCT_CREATE)` (ou `@PreAuthorize`).

| Endpoint | Permission | Rôle |
|---|---|---|
| `GET /api/permissions` | `role:manage` ou `user:manage` | Catalogue (45 permissions) |
| `GET /api/roles`, `GET /api/roles/{id}` | `role:manage` ou `user:manage` | Rôles, permissions effectives, nombre d'utilisateurs |
| `POST /api/roles` | `role:manage` | Créer un rôle personnalisé |
| `PUT /api/roles/{id}` | `role:manage` | Modifier libellé et permissions |
| `DELETE /api/roles/{id}` | `role:manage` | Supprimer un rôle personnalisé non attribué |
| `PUT /api/users/{id}/roles` | `user:manage` | Attribuer les rôles d'un utilisateur |

Règles :

- `ADMIN` détient toujours toutes les permissions (y compris les futures) et n'est pas modifiable ;
- rôles système (`ADMIN`, `GESTIONNAIRE`, `CAISSIER`) non supprimables ; un rôle attribué non plus ;
- **pas d'escalade** : on ne peut ni ajouter à un rôle, ni attribuer à un utilisateur, une permission que l'on ne détient pas (403 `PRIVILEGE_ESCALATION`) ;
- au moins un administrateur actif est conservé ;
- une modification de rôle s'applique immédiatement aux sessions ouvertes.

Ajouter une permission : migration Flyway **et** constante dans `common/security/Permissions`
(`PermissionCatalogIT` vérifie que les deux correspondent et que toute permission citée par un
endpoint existe).

## Persistance et migrations

Règles complètes (nommage, UUID v7, contraintes, soft delete, rollback) : **[docs/persistence.md](docs/persistence.md)**.

En résumé :

- le schéma n'évolue **que** par une nouvelle migration `src/main/resources/db/migration/V{NNN}__module_sujet.sql`, jamais en modifiant une migration appliquée ;
- Hibernate ne fait que **valider** le schéma (`ddl-auto=validate`) ;
- chaque migration a son script d'annulation `db/rollback/U{NNN}__*.sql`, testé automatiquement ;
- les tests `SchemaConventionsIT` refusent toute table qui ne respecte pas les conventions.

## Format des erreurs

Toutes les erreurs ont la même forme (`application/problem+json`). Le front affiche `message` :

```json
{
  "type": "urn:geststock:error:unauthenticated",
  "title": "Unauthorized",
  "status": 401,
  "code": "UNAUTHENTICATED",
  "message": "Authentification requise.",
  "detail": "Authentification requise.",
  "errors": [],
  "details": {},
  "traceId": "24ab79c855bc45dfbcbd2ba49d78194f",
  "timestamp": "2026-10-06T14:30:35.751Z",
  "path": "/api/products"
}
```

Le `traceId` est aussi renvoyé dans l'en-tête `X-Trace-Id` et figure dans chaque ligne de log.

## Structure

```
src/main/java/com/geststock/
  config/      CORS, OpenAPI, JPA (audit technique), horloge
  security/    chaîne de sécurité, réponses 401/403
  common/      entités de base, enveloppes de réponse, santé, traceId
  exception/   codes d'erreur, exceptions, gestionnaire global
  audit/ user/ role/ permission/ warehouse/   entités fondamentales (V002–V006)
  category/ brand/ product/ stock/ supplier/ purchase/ customer/ sale/
  cash/ inventory/ returns/ expense/ appointment/ report/   modules à venir
```
