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

État actuel : **socle technique, authentification JWT (`/api/auth`), utilisateurs, rôles et
permissions, catalogue (catégories, marques, produits), fournisseurs, clients (compte client),
dépôts, stock, achats (commandes, réceptions), ventes, caisse, inventaires, retours clients et corrections, alertes, journal d'audit, tableau de bord, rapports**. Sans
jeton valide, toute route protégée répond 401.

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

Lancement depuis un IDE : plutôt qu'une variable d'environnement, le mot de passe peut être placé dans
`config/application-dev.yml` (`spring.datasource.password`), lu automatiquement par Spring Boot depuis le
dossier du projet. Ce dossier est exclu de Git (`.gitignore`). Même principe pour les tests :
`config/application-test.yml` (ou `GESTSTOCK_TEST_DB_PASSWORD`) ; aucun mot de passe dans les fichiers versionnés.

| URL | Rôle |
|---|---|
| http://localhost:8080/api/health | État de l'API et de la base (`UP` / `DOWN`) |
| http://localhost:8080/swagger-ui.html | Documentation interactive (profils `dev` et `test`) |
| http://localhost:8080/v3/api-docs | Contrat OpenAPI (JSON) |

Contrat OpenAPI 3.0 complet : routes, paramètres, DTO avec règles de validation, réponses et erreurs
(`ApiError`, catalogue des codes), authentification Bearer, permission de chaque opération
(`x-permissions`), en-tête `X-Warehouse-Id`, exemples de requêtes et de réponses réels.
Guide du développeur Angular (génération du client TypeScript, conventions) : [docs/api.md](docs/api.md) ;
instantané : [docs/openapi.json](docs/openapi.json).
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

Couverture (mesure seulement) : `./mvnw verify -Pcoverage` → `target/site/jacoco-merged/index.html`.
Stratégie, styles de tests et règles métier couvertes par domaine : [docs/testing.md](docs/testing.md).
Audit de sécurité (constats, preuves, corrections, points à valider) : [docs/security-audit.md](docs/security-audit.md).

## Mise en production

Guide complet et checklist : [docs/production.md](docs/production.md).

En bref :
- image Docker (`Dockerfile`) toujours lancée en profil `prod` ;
- secrets fournis par des variables d'environnement, jamais versionnés ;
- contrôles de sécurité au démarrage ;
- sondes `/actuator/health/liveness` et `/actuator/health/readiness` ;
- journaux JSON.

Pile locale identique à la production : `deploy/docker-compose.local.yml`.

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
| `GESTSTOCK_JWT_SECRET` | aucune (clé aléatoire en dev) | oui | Clé de signature des jetons, **32 octets minimum**, aléatoire : `openssl rand -base64 48` |
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
- Fournisseur habituel (`supplierId`) facultatif, clé étrangère vers `supplier` ; il doit être actif quand on le choisit, un produit garde un fournisseur désactivé depuis.

## Fournisseurs

| Endpoint | Permission | Rôle |
|---|---|---|
| `GET /api/suppliers` | `supplier:read` | Recherche `q` (code, nom, interlocuteur, email, téléphone), `status`, `page`, `size`, `sort` (`name`, `code`, `deliveryLeadTimeDays`, `createdAt`) |
| `GET /api/suppliers/{id}` | `supplier:read` | Fiche, y compris désactivée |
| `POST /api/suppliers` | `supplier:create` | Contrat front `{name, phone, email, address, deliveryLeadTimeDays}` → 201 |
| `PUT /api/suppliers/{id}` | `supplier:update` | Modification (voir ci-dessous) |
| `DELETE /api/suppliers/{id}`, `PATCH /{id}/active` | `supplier:delete` | Désactivation (`{ok: true}`) ou réactivation, jamais d'effacement |

- Champs : `code` (unique, majuscules, généré `FOU-0001` par séquence s'il est absent), `name` (unique, casse ignorée), `contactName`, `phone`, `email`, `address`, `taxId` (NCC), `deliveryLeadTimeDays` et `paymentTermsDays` (0 à 365 jours, 0 = comptant), `note`, `active`.
- `PUT` : les champs du front sont remplacés (absent = effacé) ; les champs que le front ne connaît pas encore (`code`, `contactName`, `taxId`, `paymentTermsDays`, `note`) sont **conservés s'ils sont absents**, effacés par une chaîne vide.
- Rôles : `GESTIONNAIRE` crée et modifie, seul `ADMIN` désactive. `CAISSIER` lit (V014) : l'écran Produits charge la liste des fournisseurs.
- **Achats** : les autres modules appellent `SupplierService.getForAssignment(id, requireActive)` et reçoivent un `SupplierSnapshot` (nom, délais de livraison et de paiement). Commandes, factures, paiements et grand livre (dettes) relèvent du module achats, qui servira aussi `/api/suppliers/{id}/purchases` et `/payments`.

## Clients

| Endpoint | Permission | Rôle |
|---|---|---|
| `GET /api/customers` | `customer:read` | Recherche `q` (code, nom, email, téléphone — chiffres seuls acceptés : `0510` trouve `+225 05 10 20 30`), `status`, `type`, `priceCategory`, `page`, `size`, `sort` (`name`, `code`, `creditLimit`, `createdAt`) |
| `GET /api/customers/{id}` | `customer:read` | Fiche avec `balance` (solde dû) et `availableCredit` |
| `POST /api/customers` | `customer:create` (+ `customer:credit` si `creditLimit` > 0) | Contrat front `{name, phone, email, creditLimit}` → 201 |
| `PATCH /api/customers/{id}` (verbe du front), `PUT` | `customer:update` (+ `customer:credit` pour changer le plafond) | Modification |
| `DELETE /api/customers/{id}`, `PATCH /{id}/active` | `customer:delete` | Désactivation (`{ok: true}`) ou réactivation |
| `GET /api/customers/{id}/account`, `/account/entries` | `customer:read` | Plafond, solde dû, crédit disponible (tous dépôts ; `otherWarehousesBalance` = part née hors de vos dépôts, sans détail) ; relevé paginé limité à vos dépôts |

- Champs : `code` (unique, généré `CLI-000001`), `type` (`INDIVIDUAL`, `COMPANY`), `name`, `phone`, `email`, `address`, `city`, `taxId` (NCC), `priceCategory` (`RETAIL`, `WHOLESALE` : tarif par défaut à la vente), `creditLimit` (≥ 0, 0 = aucun crédit), `paymentTermsDays` (0–365), `note`, `active`. Pas d'unicité sur le nom ni le téléphone (homonymes, téléphone familial).
- Modification : `name`, `phone`, `email` remplacés ; `creditLimit` et les champs que le front ne connaît pas encore conservés s'ils sont absents (chaîne vide = effacé).
- **Crédit** : accorder ou modifier un plafond exige `customer:credit` (`ADMIN`, `GESTIONNAIRE`), distincte de `customer:update` que détient le caissier (V016). Le caissier peut renvoyer le plafond inchangé.
- **Compte client** (`customer_account_entry`, en ajout seul) : relevé des créances, pas une comptabilité. Seul `CustomerAccountService` l'écrit, sous verrou du client :
  - `recordCreditSale` (ventes) : client actif, solde dû après écriture ≤ plafond (`422 CREDIT_LIMIT_EXCEEDED`) ;
  - `recordCredit(PAYMENT | CREDIT_NOTE | SALE_CANCELLATION)` (paiements, retours, annulations) : montant ≤ solde dû, pas de trop-perçu (`422 AMOUNT_EXCEEDS_CUSTOMER_BALANCE`), possible sur un client désactivé ;
  - une pièce n'est imputée qu'une fois (`uk_customer_account_entry_source`).
- **Dépôts** : plafond et solde restent globaux ; chaque écriture porte le dépôt de sa pièce (V031), le relevé détaillé est limité au périmètre de l'utilisateur. Voir [docs/multi-depots.md](docs/multi-depots.md).

## Dépôts

| Endpoint | Permission | Rôle |
|---|---|---|
| `GET /api/warehouses` | `warehouse:read` | Dépôts accessibles (`{items, total}`, contrat du front) ; filtres `q`, `type`, `status`, pagination |
| `GET /api/warehouses/current` | `warehouse:read` | Dépôt retenu par le serveur pour l'en-tête `X-Warehouse-Id` |
| `GET /api/warehouses/{id}` | `warehouse:read` | 404 si hors périmètre |
| `POST /api/warehouses` | `warehouse:manage` | `{"name"}` suffit (front) : code généré `MAG-nn` / `DEP-nn`, type `STORE` |
| `PUT /api/warehouses/{id}` | `warehouse:manage` | Code, nom (uniques), type, adresse, téléphone |
| `PATCH /api/warehouses/{id}/active`, `DELETE` | `warehouse:manage` | Désactivation, jamais d'effacement |
| `POST /api/warehouses/{id}/main` | `warehouse:manage` + `warehouse:all` | Change le dépôt principal (dépôt actif, sinon `422 MAIN_WAREHOUSE_INACTIVE`) |

- **Dépôt principal** (V012) : un seul (index unique partiel), toujours actif ; `MAG-01` à l'installation. Il ne peut pas être désactivé (`409 MAIN_WAREHOUSE_REQUIRED`) avant d'en désigner un autre.
- **Périmètre** (calculé par le serveur, relu à chaque requête) : `warehouse:all` → tous les dépôts ; sinon dépôts attribués + dépôt par défaut de l'utilisateur ; utilisateur sans affectation → dépôt principal. `warehouse:manage` administre les dépôts de son périmètre sans élargir la visibilité des données ; `GESTIONNAIRE` n'a plus ni `warehouse:all` ni `warehouse:manage` (V029).
- **Dépôt courant** : l'en-tête `X-Warehouse-Id` est un *choix* parmi les dépôts autorisés. Une valeur fournie n'est jamais remplacée : pas un UUID (ancien `wh_1`) → `400 INVALID_WAREHOUSE_ID`, inconnu → `400 UNKNOWN_WAREHOUSE`, hors périmètre → `403 WAREHOUSE_ACCESS_DENIED`, désactivé → `403 WAREHOUSE_INACTIVE`. En-tête **absent** seulement → dépôt par défaut, puis premier dépôt attribué, puis dépôt principal (sans affectation ou avec `warehouse:all`), sinon `403 NO_WAREHOUSE_AVAILABLE`. Le dépôt retenu est renvoyé dans l'en-tête de réponse `X-Warehouse-Id`.
- **Écritures** : une action sur une pièce d'un autre dépôt que le dépôt courant, ou un `warehouseId` du corps différent, → `409 WAREHOUSE_MISMATCH` ; vues tous dépôts (`allWarehouses=true`) réservées à `warehouse:all`.
- **Nouveau module** : déclarer `@CurrentWarehouse WarehouseContext warehouse`, rattacher la pièce à `warehouse.id()`, appeler `WarehouseAccess.requireCurrent(pièce, courant)` avant toute action sur une pièce existante et `requireAccessible(id)` pour un dépôt de destination.
- Règles complètes, migrations et déploiement : **[docs/multi-depots.md](docs/multi-depots.md)**.

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
| `POST /api/warehouses/transfer` `{fromWarehouseId, toWarehouseId, items: [{productId, quantity}], note}` (ou `productId` + `quantity` pour un seul produit ; réponse `{ok: true, transfer, …}`) | `stock:transfer` + accès aux deux dépôts |
| `GET /api/stock/transfers` (`direction=SENT\|RECEIVED`, `q`, `status`, `productId`, `from`, `to`, `allWarehouses`) | `stock:read` (+ `warehouse:all` pour `allWarehouses`) |
| `GET /api/stock/transfers/{id}` (reçu) | `stock:read` |
| `POST /api/stock/transfers/{id}/return` `{reason}` | `stock:transfer` + accès aux deux dépôts, depuis l'un d'eux |

**Transferts** (V043, V044) : chaque transfert est une pièce `stock_transfer` numérotée `TRF-AAAA-nnnnnn` (reçu), visible depuis ses deux dépôts, avec une ou plusieurs lignes `stock_transfer_line` (un produit au plus une fois). Tous les produits passent ensemble ou aucun. Le retour renvoie toutes les lignes au dépôt d'origine, une seule fois (statut `RETURNED`), par de nouveaux mouvements rattachés à la même pièce ; refusé si la destination n'a plus l'un des produits.

**Garde-fous PostgreSQL** (V013) : `stock_movement` en ajout seul ; une ligne de `stock_level` naît à zéro, ne peut être supprimée et ne varie que si elle désigne un nouveau mouvement du même couple qui explique exactement la variation (`fn_stock_level_guard`) ; `CHECK quantity >= 0`. Une écriture SQL directe est refusée par la base.

Pas de stock négatif : aucune règle métier ne l'autorise aujourd'hui (l'introduire exigerait de modifier `StockService` et la contrainte `ck_stock_level_quantity`).

## Achats

Workflow : **commande → réception(s) → stock**.

```
ORDERED ──réception partielle──► PARTIALLY_RECEIVED ──reste──► RECEIVED
   │ (rien reçu)                        │
   └──annulation──► CANCELLED           └──solde du reliquat──► CLOSED
```

| Endpoint | Permission | Rôle |
|---|---|---|
| `GET /api/purchases/orders` | `purchase:read` | Commandes du dépôt courant (`allWarehouses=true` avec `warehouse:all`) ; filtres `q`, `status`, `supplierId` ; pagination, tri |
| `GET /api/purchases/orders/{id}`, `/{id}/receipts` | `purchase:read` | Commande ; ses réceptions avec le mouvement de stock de chaque ligne |
| `POST /api/purchases/orders` | `purchase:create` | Contrat front `{supplierId, lines: [{productId, quantity, unitPurchasePrice}]}` → 201 |
| `POST /api/purchases/orders/{id}/receive` | `purchase:receive` | `{}` (bouton du front) : tout le reste ; `lines: [{productId, quantity}]` : réception partielle |
| `POST /api/purchases/orders/{id}/cancel`, `/close` | `purchase:create` | Annulation (rien reçu) ; solde du reliquat (reçue en partie) |
| `GET /api/suppliers/{id}/purchases` | `purchase:read` | Historique d'achats du fournisseur : une entrée par réception |

**Commande** (calculs serveur) : fournisseur et produits actifs, un produit par ligne, quantités entières > 0. Coût unitaire = `unitPurchasePrice` (≥ 0, 2 décimales) ou, à défaut, prix d'achat du produit ; totaux de ligne et de commande calculés par le serveur (les totaux envoyés sont ignorés). Livraison dans le dépôt courant (`warehouseId` du corps : le même, sinon `409 WAREHOUSE_MISMATCH`), prévue à aujourd'hui + délai du fournisseur. Numéro `CMD-AAAA-nnnnnn`.

**Réception** (`GoodsReceiptService`, une seule transaction) : verrou de la commande, contrôle du reste à recevoir (pas de sur-réception, `422 RECEIPT_QUANTITY_EXCEEDS_REMAINING`), bon `REC-AAAA-nnnnnn`, **mouvements `RECEIPT` via `StockService`** dans le dépôt de la commande au coût de la commande, lignes reçues liées à leur mouvement, quantités reçues, statut, puis dernier prix d'achat des produits (RG-PUR-5). Tout ou rien.

**Garde-fous PostgreSQL** (V015) : réceptions en ajout seul ; une ligne reçue doit désigner le mouvement `RECEIPT` exact qu'elle a produit (`fn_goods_receipt_line_guard`) ; contrainte différée au COMMIT : quantité reçue d'une ligne = somme de ses lignes reçues (`fn_purchase_receipt_consistency`). Marquer une commande reçue sans entrée en stock est refusé par la base.

Contrat front : `status` vaut `PENDING` tant qu'une réception est possible, `DELIVERED` ensuite (`CANCELLED` si annulée) ; `documentStatus` donne le statut détaillé. `paidAmount` vaut 0 : facture (`/invoice`), paiements (`/pay`, `/api/suppliers/{id}/payments`) et coût moyen pondéré (décision I.9) viendront ensuite.

## Ventes

| Endpoint | Permission | Rôle |
|---|---|---|
| `GET /api/sales` | `sale:read` | Ventes du dépôt courant (`allWarehouses=true` avec `warehouse:all`) ; filtres `q` (numéro, client), `status`, `customerId`, `paymentMethod`, `from`, `to` ; pagination, tri |
| `GET /api/sales/{id}` | `sale:read` | Vente et ses lignes (avec la sortie de stock de chacune) |
| `POST /api/sales` | `sale:create` | `{type?, customerId?, warehouseId?, paymentMethod, paidAmount?, items: [{productId, quantity, discountPercent?}]}` → 201 |
| `POST /api/sales/{id}/cancel` | `sale:cancel` | `{reason}` : remise en stock et annulation de la créance |
| `GET /api/customers/{id}/sales` | `sale:read` | Ventes d'un client, tous dépôts |

**Le client n'impose jamais un prix** : `unitPrice`, `purchasePrice`, `total`, `profit` envoyés sont ignorés. `SaleService.create`, en **une transaction** :

1. produits relus (existants, actifs), prix du tarif (`RETAIL` = détail, `WHOLESALE` = gros ; par défaut le tarif du client) — `422 PRODUCT_PRICE_NOT_SET` si nul ;
2. remise par ligne ≤ `geststock.sales.max-discount-percent` (20 % par défaut, `422 DISCOUNT_NOT_ALLOWED`) ; coût = prix d'achat de référence ; montants arrondis au centime ;
3. règlement : 0 ≤ `paidAmount` ≤ total (total par défaut), mode de paiement requis si un montant est payé ; un reste dû exige un client (`422 CUSTOMER_REQUIRED_FOR_CREDIT`) ;
4. vente `VTE-AAAA-nnnnnn` au nom de l'utilisateur connecté, dans le dépôt courant (`warehouseId` du corps : le même, sinon `409 WAREHOUSE_MISMATCH`) ;
5. **sorties de stock `SALE` via `StockService`** (sous verrou, `422 STOCK_INSUFFICIENT`) ; lignes liées à leur sortie ;
6. reste dû porté au compte du client (`CustomerAccountService`, plafond contrôlé sous verrou, `422 CREDIT_LIMIT_EXCEEDED`) ;
7. trace `CREATE SALE` dans `audit_log` (`AuditService`, même transaction).

Au moindre refus, rien n'est écrit (ni vente, ni stock, ni créance, ni audit). Annulation : `SALE_CANCELLATION` par ligne, créance annulée, trace `CANCEL` ; refusée (`409 SALE_CREDIT_ALREADY_SETTLED`) si le client a déjà réglé une partie du crédit (passer par un retour), ou si la vente a déjà des retours (`409 SALE_HAS_RETURNS`).

**Garde-fous PostgreSQL** (V017) : montants figés, seule l'annulation est permise (`fn_sale_guard`) ; une ligne vendue désigne sa sortie de stock exacte (`fn_sale_line_guard`) ; au COMMIT (`fn_sale_consistency`) : totaux = lignes, crédit ⇒ créance, annulation ⇒ remise en stock et créance annulée ; reste dû ⇒ client (`ck_sale_credit_customer`).

Une vente payée est encaissée dans la caisse ouverte du vendeur (`409 NO_OPEN_CASH_SESSION` sinon, vérifié avant toute écriture) ; l'annulation d'une vente payée rembourse depuis la caisse de l'utilisateur. À venir : TVA et règle définitive des remises (décision I.7).

## Caisse

Pas de caisse globale : **chaque utilisateur ouvre sa caisse** dans le dépôt courant (au plus une ouverte à la fois, poste facultatif). Chaque mouvement d'argent est une écriture **en ajout seul** portant : référence `OPC-AAAA-nnnnnn`, session, utilisateur, date, type, sens, moyen de paiement, montant et pièce d'origine.

| Endpoint | Permission | Rôle |
|---|---|---|
| `GET /api/cash-register/current` | `cash:operate` | Ma caisse ouverte (corps `null` sinon), avec `warehouseId` et `inCurrentWarehouse` |
| `POST /api/cash-register/open` | `cash:operate` | `{openingBalance, terminal?, note?}` → 201 |
| `POST /api/cash-register/operations` | `cash:operate` | Apport `IN` / sortie `OUT` d'espèces `{type, amount, note?}` → 201 |
| `POST /api/cash-register/operations/{id}/reversal` | `cash:operate` | Correction d'un apport ou d'une sortie de ma caisse ouverte `{reason}` → 201 |
| `POST /api/cash-register/close` | `cash:operate` | `{countedCash, note?}` : attendu et écart figés |
| `GET /api/cash-register/sessions`, `/sessions/{id}`, `/sessions/{id}/operations` | `cash:operate` (ses caisses) ou `cash:read` (toutes, filtre `cashierId`) | Historique paginé |
| `GET /api/customers/{id}/payments` | `customer:read` | Règlements d'un client |
| `POST /api/customers/{id}/payments` | `customer:pay` | `{amount, paymentMethod?}` : encaissé dans ma caisse du dépôt courant, dette diminuée |

| Type d'écriture | Sens | Origine |
|---|---|---|
| `SALE` | entrée | vente payée (tout moyen de paiement) |
| `CUSTOMER_PAYMENT` | entrée | règlement de dette client (refusé au-delà de la dette) |
| `CASH_IN` | entrée | apport manuel d'espèces |
| `CORRECTION_IN` | entrée | correction d'une sortie manuelle (`CASH_OUT`) |
| `SALE_REFUND` | sortie | annulation d'une vente payée |
| `RETURN_REFUND` | sortie | remboursement d'un retour client |
| `CORRECTION_OUT` | sortie | correction d'un apport manuel (`CASH_IN`) |
| `CASH_OUT` | sortie | sortie manuelle d'espèces |

- **Espèces attendues** = fond + entrées en espèces − sorties en espèces ; récapitulatif `cashSalesTotal`, `totalIn`, `totalOut`, `expectedCash` (contrat front) et `totalsByMethod` (rapprochement Mobile Money, virements).
- Écritures sous verrou de la session : une sortie d'espèces ne dépasse jamais les espèces attendues (`422 CASH_INSUFFICIENT`) ; toute opération se fait depuis le dépôt de la caisse, et une pièce s'encaisse dans une caisse de son dépôt (`409 CASH_SESSION_OTHER_WAREHOUSE` ; garanti en base par V030) ; une pièce n'est encaissée qu'une fois.
- Seul le titulaire écrit dans sa caisse et la clôture. Ouverture, clôture, apports, sorties et corrections sont tracés dans `audit_log`.
- **Correction sans suppression** : une écriture erronée n'est jamais modifiée ni supprimée ; la contre-passation (même montant, sens inverse, motif obligatoire) se fait dans la caisse encore ouverte, une seule fois (`409 CASH_ENTRY_ALREADY_REVERSED`), et seulement pour les apports et sorties manuels (`409 CASH_ENTRY_NOT_REVERSIBLE` : une vente se corrige par annulation ou retour). Garde-fou `fn_cash_correction_guard` (V020).
- **Garde-fous PostgreSQL** (V018) : écriture seulement dans une session ouverte et par son titulaire (`fn_cash_entry_guard`) ; session clôturée figée (`fn_cash_session_guard`) ; au COMMIT : vente payée ⇒ encaissement, vente payée annulée ⇒ remboursement (`fn_sale_cash_consistency`), règlement au compte client ⇔ encaissement (`fn_customer_payment_consistency`).

## Inventaires

```
DRAFT ──start──► IN_PROGRESS ──1er comptage──► COUNTING ──submit──► VALIDATION ──validate──► COMPLETED
                                                   ▲                    │
                                                   └──────reopen────────┘          (cancel avant COMPLETED → CANCELLED)
```

| Endpoint | Permission | Rôle |
|---|---|---|
| `GET /api/inventories`, `/{id}` | `inventory:read` | Inventaires du dépôt courant (`allWarehouses=true` avec `warehouse:all`), filtres `status`, `from`, `to` |
| `POST /api/inventories` | `inventory:create` | Brouillon : `scope` `FULL` (produits actifs), `CATEGORY` (`categoryId`) ou `SELECTION` (`productIds`) |
| `POST /{id}/products`, `DELETE /{id}/products/{productId}` | `inventory:create` | Périmètre (brouillon uniquement) |
| `POST /{id}/start` | `inventory:create` | Périmètre figé |
| `PUT /{id}/counts` | `inventory:create` | `{counts: [{productId, countedQuantity, note?}]}` ; recomptage possible |
| `POST /{id}/submit` | `inventory:create` | Comptage clos : tous les produits comptés (`422 INVENTORY_LINES_NOT_COUNTED`) |
| `POST /{id}/reopen` | `inventory:validate` | Renvoi au comptage |
| `POST /{id}/validate` | **`inventory:validate`** | Écarts appliqués au stock, inventaire terminé |
| `POST /{id}/cancel` | `inventory:create` | Avant validation ; aucun effet sur le stock |
| `GET/POST /api/inventory/sessions` (contrat front) | `inventory:read` / `inventory:create` | `POST {note, lines: [{productId, physicalQuantity}]}` : tout le workflow en une transaction ; validé seulement si l'utilisateur détient `inventory:validate` |

- **Quantité théorique** relevée par le serveur dans le stock du dépôt au moment de chaque comptage ; écart = compté − théorique.
- **Validation transactionnelle** : inventaire verrouillé, chaque écart non nul appliqué par `StockService` (mouvement `INVENTORY`, source l'inventaire) en un seul appel atomique, lignes liées à leur mouvement, inventaire terminé, audit. Au moindre refus (`422 STOCK_INSUFFICIENT` si le stock a baissé depuis le comptage), rien n'est appliqué : renvoyer au comptage.
- L'ajustement applique **l'écart mesuré au comptage** : une vente faite entre le comptage et la validation n'est pas effacée.
- Au plus **un inventaire ouvert par dépôt** (`409 INVENTORY_ALREADY_OPEN`).
- **Garde-fous PostgreSQL** (V019) : transitions autorisées et inventaire terminé ou annulé figé (`fn_inventory_session_guard`) ; lignes ajoutées en brouillon, comptées en cours, liées à leur mouvement en validation (`fn_inventory_line_guard`) ; au COMMIT, un inventaire terminé a toutes ses lignes comptées et chaque écart son mouvement exact (`fn_inventory_consistency`).

## Retours clients et corrections commerciales

Une vente validée n'est **jamais supprimée ni modifiée** : elle se corrige par des opérations inverses, rattachées à elle et tracées.

| Correction | Endpoint | Permission | Opérations inverses (une transaction) |
|---|---|---|---|
| Annulation totale | `POST /api/sales/{id}/cancel` `{reason}` | `sale:cancel` | Statut `CANCELLED` (vente conservée), remise en stock `SALE_CANCELLATION`, créance annulée, remboursement `SALE_REFUND` |
| Retour (partiel ou total) | `POST /api/sales/{id}/returns` | `return:create` | Avoir `RET-AAAA-nnnnnn`, remise en stock `CUSTOMER_RETURN` des articles revendables, remboursement `RETURN_REFUND` ou avoir `CREDIT_NOTE` sur le compte client |
| Erreur de caisse | `POST /api/cash-register/operations/{id}/reversal` `{reason}` | `cash:operate` | Contre-passation `CORRECTION_IN` / `CORRECTION_OUT` |

Consultation (`sale:read`) : `GET /api/sales/{id}/returns`, `GET /api/returns` (dépôt courant, filtres `customerId`, `settlement`, `from`, `to`, `allWarehouses`), `GET /api/returns/{id}`.

```json
POST /api/sales/{id}/returns
{"settlement": "REFUND", "refundMethod": "CASH", "reason": "Charnière cassée",
 "items": [{"productId": "…", "quantity": 1, "condition": "DEFECTIVE"}]}
```

- **Montants calculés par le serveur** au prix net de la vente (remise comprise), au prorata ; le dernier article retourné d'une ligne reprend le reliquat (pas d'écart d'arrondi).
- **Jamais plus que vendu**, retours précédents compris (`422 RETURN_QUANTITY_EXCEEDS_SOLD`) : la vente est verrouillée, deux retours simultanés se sérialisent.
- `RESELLABLE` : remis en stock au coût de la vente ; `DEFECTIVE` : pas de remise en stock (aucune perte supplémentaire à saisir).
- `REFUND` : depuis la caisse ouverte de l'utilisateur, moyen de la vente par défaut, plafonné à ce que le client a payé sur la vente (`422 RETURN_REFUND_EXCEEDS_PAID`). `ACCOUNT_CREDIT` : client obligatoire (`422 RETURN_CUSTOMER_REQUIRED`), avoir imputé sur sa dette, sans trop-perçu (`422 AMOUNT_EXCEEDS_CUSTOMER_BALANCE`).
- Vente annulée : pas de retour (`409 SALE_NOT_RETURNABLE`) ; vente avec retours : plus d'annulation (`409 SALE_HAS_RETURNS`).
- Au moindre refus, rien n'est écrit (ni avoir, ni stock, ni caisse, ni compte, ni audit). Trace `CREATE` / `CUSTOMER_RETURN` avec motif, montant et règlement.
- **Garde-fous PostgreSQL** (V020) : avoirs et lignes en ajout seul ; une ligne reprend la ligne de vente (produit, coût) et sa remise en stock exacte (`fn_customer_return_line_guard`) ; au COMMIT (`fn_customer_return_consistency`) : total = lignes, cumul ≤ vendu, vente non annulée, remboursement en caisse ou avoir au compte du montant exact ; une vente avec retours ne passe plus `CANCELLED` (`fn_sale_return_guard`) ; un retour est dans le dépôt de sa vente (V034).
- Non couvert : retours fournisseurs, avoir « porte-monnaie » (crédit au-delà de la dette), échange direct (retour + nouvelle vente).

## Alertes

Alertes **calculées à la demande** à partir des données existantes : aucune table d'alertes, aucune notification externe. Une alerte disparaît d'elle-même quand sa cause est réglée (réapprovisionnement, réception, règlement, clôture…).

| Type | Domaine | Gravité | Condition |
|---|---|---|---|
| `OUT_OF_STOCK` | stock | CRITICAL | stock ≤ 0 (produit actif ; un produit jamais approvisionné n'est signalé qu'au dépôt principal, voir [décision A](docs/multi-depots.md#alertes--produit-jamais-approvisionné-décision-a)) — « Rupture » du front |
| `BELOW_MINIMUM` | stock | WARNING | 0 < stock ≤ seuil minimum — « Stock faible » du front (`stock ≤ alertThreshold`) |
| `LOW_STOCK` | stock | INFO | seuil < stock ≤ seuil + marge (`lowStockMarginPercent`, au moins 1 unité) |
| `OVERSTOCK` | stock | INFO | stock > stock maximum du produit (`maxStockLevel`) |
| `INACTIVE_PRODUCT_IN_STOCK` | stock | WARNING | produit désactivé dont il reste du stock (immobilisé, invendable) |
| `DORMANT_STOCK` | stock | INFO | stock sans aucune sortie (vente, transfert) depuis `dormantStockDays` |
| `PURCHASE_ORDER_OVERDUE` | achats | WARNING | commande non soldée après la date prévue + `purchaseOverdueGraceDays` |
| `CUSTOMER_PAYMENT_OVERDUE` | clients | WARNING | créance échue : délai du client (ou `defaultPaymentTermsDays`) dépassé ; règlements imputés aux plus anciennes ventes |
| `CUSTOMER_CREDIT_LIMIT` | clients | INFO, WARNING si atteint | dette ≥ `creditLimitWarningPercent` % du plafond |
| `CASH_SESSION_OPEN_TOO_LONG` | caisse | WARNING | caisse ouverte depuis plus de `cashSessionMaxOpenHours` |
| `CASH_DIFFERENCE` | caisse | WARNING | clôture des `cashDifferenceLookbackDays` derniers jours avec un écart > `cashDifferenceTolerance` |
| `INVENTORY_AWAITING_VALIDATION` | inventaire | INFO | inventaire compté, en attente de validation |

| Endpoint | Permission | Rôle |
|---|---|---|
| `GET /api/alerts` | un domaine surveillé (`stock:read`, `purchase:read`, `customer:read`, `cash:read`, `inventory:read`) | Alertes du dépôt courant (les alertes clients ne dépendent pas du dépôt), de la plus grave à la moins grave ; filtres `category`, `type`, `severity` (répétables), `allWarehouses` (avec `warehouse:all`), `page`/`size` |
| `GET /api/alerts/summary` | idem | Tableau de bord : `total`, `stockAlertsCount` (ruptures + seuils atteints, le `DailyReport.stockAlertsCount` du front), compteurs `bySeverity`/`byCategory`/`byType` (à 0 compris), `top` (10 plus graves) |
| `GET /api/alerts/settings` | idem | Seuils courants |
| `PUT /api/alerts/settings` | **`alert:configure`** | Modification à chaud, tracée dans l'audit ; 0 désactive l'alerte concernée |

- Chacun ne voit que les domaines qu'il peut consulter et les dépôts auxquels il a accès.
- Alerte : `{id, type, category, severity, title, message, warehouseId, warehouseName, entityType, entityId, entityLabel, value, threshold, since}` ; `id` est une clé stable (`TYPE:dépôt:entité`), `message` une phrase prête à afficher.
- **Seuils** : par produit (`alertThreshold` = seuil minimum, `maxStockLevel`), et globaux dans `alert_setting` (V021, valeurs par défaut : seuil minimum par défaut 0, marge 50 %, dormance 90 j, tolérance commandes 0 j, délai de paiement 30 j, plafond 90 %, caisse 24 h, écart 1 000 FCFA sur 7 j).
- Non couvert (données absentes) : péremption et lots, notifications (e-mail, SMS, WhatsApp), accusé de lecture des alertes.

## Tableau de bord

Indicateurs **calculés à chaque appel sur PostgreSQL** (aucune donnée simulée) ; règles de calcul détaillées dans [docs/dashboard.md](docs/dashboard.md). Permission `report:read`, dépôt courant ou `allWarehouses=true` (avec `warehouse:all`).

| Endpoint | Contenu |
|---|---|
| `GET /api/dashboard` | Forme du `DashboardVm` du front : `kpis` du jour (`todaySalesTotal` = CA net, `todayProfit` = marge brute, `stockTotal`), ventes 14 jours, top 8 produits sur 30 jours, stock 7 jours |
| `GET /api/dashboard/summary` | Ventes (CA brut, retours, CA net, coût, marge brute, taux de marge, panier moyen, annulations), achats (réceptions, commandes), stock (unités, valeur, ruptures, seuils) d'une période |
| `GET /api/dashboard/sales-by-day`, `/top-products`, `/stock-evolution` | Séries paramétrables (`from`, `to`, `days`, `limit`) |

- CA net = ventes validées − retours du jour du retour ; marge brute = CA net − coût (coût figé à la vente, articles revendables retournés déduits).
- Évolution du stock **reconstituée depuis le journal des mouvements** (stock actuel − mouvements postérieurs), en remplacement de la courbe fictive du front.
- Fuseau des jours : `geststock.dashboard.time-zone` (défaut `Africa/Abidjan`).

## Rapports

Rapports serveur paginés (`/api/reports/*`, `report:read`) ; filtres, champs et règles de calcul : [docs/reports.md](docs/reports.md).

| Domaine | Endpoints |
|---|---|
| Ventes | `/sales` (liste), `/sales/by-product`, `/sales/by-period` (`groupBy` DAY / MONTH / YEAR), `/sales/by-seller`, `/sales/by-warehouse` |
| Achats | `/purchases` (commandes : reçu, reste, retards), `/suppliers` (synthèse fournisseur, délai constaté) |
| Stock | `/stock` (valorisation, rotation, couverture, dormants), `/stock-movements`, `/stock-movements/by-type` |
| Exploitation | `/inventories` (écarts valorisés), `/cash-sessions` (attendu, compté, écart), `/customers` (créances, part échue) |

- Filtres selon pertinence : période (`from`, `to`), dépôt (courant, `warehouseId`, `allWarehouses`), `productId`, `categoryId`, `userId`, `customerId`, `supplierId`, `status`, `type`, `stockState`, `withBalance`.
- Base de données seule : comptage, tri (liste blanche), `LIMIT`/`OFFSET` et totaux (`summary`) en SQL ; 200 lignes au plus par page.

## Journal d'audit

Journal métier persisté dans `audit_log` (V006, module ajouté en V022), **en ajout seul** : la base refuse toute modification ou suppression, et l'API n'a aucune route d'écriture.

Chaque entrée : utilisateur (`userId`, `username`, `userRole`), `action`, `module`, `entityType`, `entityId`, `entityLabel`, date, résultat (`SUCCESS` | `FAILURE`), `details`, et selon le cas `before`/`after` et `changes` (`[{field, oldValue, newValue}]`), `meta` (`{errorCode, httpStatus}` pour un échec), IP, navigateur, dépôt, `traceId`.

- **Identité infalsifiable** : l'auteur est relevé par `AuditService` dans le `SecurityContext` (utilisateur du jeton, rôles relus en base) ; aucune méthode n'accepte un auteur fourni par l'appelant, et les champs ou en-têtes du client (`userId`, `X-User-Id`…) sont ignorés. L'IP est celle de la connexion (`X-Forwarded-For` n'est pris en compte qu'en prod, via `forward-headers-strategy`). Connexion : identité vérifiée par le mot de passe ; échec de connexion : aucun auteur, le nom saisi n'est qu'un libellé ; déconnexion : propriétaire du jeton révoqué.
- **Réussites** : écrites par le service métier **dans la transaction de l'opération** (une opération annulée ne laisse pas de trace « réussie »). **Échecs** (droits, validation, règle métier, conflit, erreur serveur) : écrits dans une transaction indépendante par l'intercepteur des points d'entrée `@Audited`, avec le code d'erreur de l'API.
- **Données sensibles** : mots de passe, jetons, secrets et empreintes masqués (`***`) dans les états ; un mot de passe n'apparaît jamais, même haché.

| Module | Opérations tracées |
|---|---|
| `AUTH` | `LOGIN` (réussi / refusé, compte verrouillé ou désactivé), `LOGOUT`, `PASSWORD_CHANGE` |
| `USER` | `CREATE`, `UPDATE`, `ASSIGN_ROLES`, `ACTIVATE` / `DEACTIVATE`, `DELETE`, `PASSWORD_RESET` (avant/après) |
| `SECURITY` | rôles : `CREATE`, `UPDATE` (permissions ajoutées / retirées), `DELETE` |
| `CATALOG` | produits, catégories, marques : `CREATE`, `UPDATE` (avant/après : prix, seuils…), `ACTIVATE` / `DEACTIVATE` |
| `WAREHOUSE`, `SUPPLIER`, `CUSTOMER` | `CREATE`, `UPDATE`, `ACTIVATE` / `DEACTIVATE`, `SET_MAIN` ; client : `UPDATE_CREDIT_LIMIT` (donnée sensible), `PAY` |
| `STOCK` | mouvement manuel `CREATE`, `REVERSAL`, `TRANSFER` (quantités avant/après) |
| `PURCHASE` | commande `CREATE`, `CANCEL`, `CLOSE` ; réception `RECEIVE` (lignes, prix d'achat) |
| `SALE`, `RETURN` | vente `CREATE`, `CANCEL` ; retour `CREATE` |
| `INVENTORY` | `CREATE`, `ADD_PRODUCTS`, `REMOVE_PRODUCT`, `START`, `COUNT`, `SUBMIT`, `REOPEN`, `VALIDATE`, `CANCEL` |
| `CASH` | `OPEN`, `CASH_IN`, `CASH_OUT`, `CORRECTION`, `CLOSE` |
| `ALERT`, `AUDIT` | seuils des alertes `UPDATE` ; export du journal `EXPORT` |

| Endpoint (`audit:read`) | Rôle |
|---|---|
| `GET /api/audit-logs` | Du plus récent au plus ancien ; filtres `dateFrom`, `dateTo` (`AAAA-MM-JJ` inclus ou instant ISO), `actions`, `entityTypes`, `modules` (`A,B`), `userId`, `status`, `entityId`, `search`, `warehouseId` ; `page`, `size`. Sans `warehouse:all` : entrées de vos dépôts seulement (liste, détail, statistiques, export) |
| `GET /api/audit-logs/{id}` | Détail, `changes`/`before`/`after`/`meta` en JSON |
| `GET /api/audit-logs/stats` | `{totalToday, failuresLast7Days, mostActiveUser, mostFrequentAction}` (30 jours) |
| `GET /api/audit-logs/export` | CSV `;` UTF-8 (BOM), en-tête du front + `Module`, 50 000 lignes max ; l'export est tracé |

Ajouter une opération : `audit.record(AuditEvent.of(action, entityType, id, libellé).before(...).after(...))` dans la transaction du service, `@Audited(action, entityType)` sur le point d'entrée, et le type d'entité dans `AuditModule`.

## Rôles et permissions (RBAC)

Modèle : **utilisateur → rôles → permissions** `domaine:action`. Les permissions sont calculées
par le serveur à chaque requête ; un endpoint les exige avec
`@RequiresPermission(Permissions.PRODUCT_CREATE)` (ou `@PreAuthorize`).

| Endpoint | Permission | Rôle |
|---|---|---|
| `GET /api/permissions` | `role:manage` ou `user:manage` | Catalogue (47 permissions) |
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
  category/ brand/ product/ supplier/ stock/ purchase/ customer/ sale/ cash/ inventory/ returns/ alert/ report/   catalogue … tableau de bord (V010–V023)
  expense/ appointment/   modules à venir
```
