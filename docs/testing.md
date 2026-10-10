# Stratégie de tests du backend

Les tests vérifient des **règles métier** (stock jamais négatif, prix calculés par le serveur,
annulation qui remet le stock, droits par rôle…) sur une vraie base PostgreSQL, avec ses
contraintes et triggers. Pas de base en mémoire : les garde-fous SQL (contraintes différées,
tables en ajout seul, verrous) font partie de ce qui est testé.

## Lancer

```bash
./mvnw test                                                   # tests unitaires (*Test)
GESTSTOCK_TEST_TESTCONTAINERS=false ./mvnw verify             # + intégration (*IT) sur PostgreSQL local
GESTSTOCK_TEST_TESTCONTAINERS=false ./mvnw verify -Pcoverage  # + couverture : target/site/jacoco-merged/index.html
```

Sans `GESTSTOCK_TEST_TESTCONTAINERS=false`, les tests d'intégration démarrent PostgreSQL dans
Docker (Testcontainers).

## Trois styles de tests d'intégration

| Style | Quand | Exemple |
|---|---|---|
| `@Transactional` + MockMvc | Contrat HTTP, validation, droits, cas nominaux ; annulé en fin de test. Les contraintes différées sont forcées par `set constraints all immediate`. | `SaleApiIT` |
| Transactions réelles (COMMIT / ROLLBACK) | Atomicité quand un refus survient **après** des écritures, concurrence (threads simultanés), garde-fous SQL au COMMIT ; purge explicite. | `SaleTransactionIT`, `scenario.*` |
| Tranche web / unitaire | Règles pures (prix, seuils, machines à états), sans base. | `SalePricingTest`, `InventorySessionTest` |

Outils : `StockFixtures` (dépôts, produits, utilisateurs, invariant « niveau = somme des
mouvements »), `BusinessScenario` (exécution au nom d'un utilisateur, tâches simultanées, purge
de toutes les pièces), `WithPermissions` (contexte de sécurité de test).

## Couverture par domaine

| Domaine | Tests principaux | Règles vérifiées |
|---|---|---|
| Unitaires | `SalePricingTest`, `AlertRulesTest`, `CashSummaryTest`, `PurchaseOrderTest`, `InventorySessionTest`, `AuditDiffTest`, `DashboardServiceTest`, `ReportPeriodTest`, `PasswordPolicyTest`, `SortWhitelistTest` | Remises plafonnées, seuils et marges, espèces attendues, états des commandes et inventaires, masquage des secrets, périodes |
| Services | `*ServiceTest` (Mockito), `UserAccountServiceIT`, `StockServiceIT` | Unicité, escalade de privilèges, administrateur initial, mot de passe, application des mouvements |
| Repositories | `AppUserRepositoryIT`, `RoleRepositoryIT`, `WarehouseRepositoryIT`, `AuditLogRepositoryIT` | Requêtes, JSONB, journal non modifiable |
| Contrôleurs | `*ApiIT` (MockMvc), `UserControllerTest`, `GlobalExceptionHandlerTest` | Contrat du front, codes HTTP, format `ApiError`, validation |
| Sécurité | `SecurityIT`, `EndpointSecurityCoverageIT`, `WarehouseScopeIT`, `AuthAuditIT`, `AdminBootstrapTest` | JWT, rotation des jetons, verrouillage, désactivation immédiate, aucun compte par défaut, routes toutes protégées |
| Permissions | `RolePermissionMatrixIT`, `PermissionCatalogIT`, tests « droits » de chaque `*ApiIT` | Matrice des rôles livrés avec de **vrais jetons** : caissier vend mais n'annule pas, responsable corrige, seul l'admin administre |
| Intégration | `*ApiIT`, `scenario.StockLifecycleIT`, `OpenApiDocumentationIT` | Parcours complets, cohérence API / DTO / validation / documentation |
| Transactions | `*TransactionIT`, `scenario.CrossModuleRollbackIT`, `PurchaseReceiptIT` | Refus après écritures → rien n'est conservé (stock, pièce, caisse, compte, audit) |
| Stock | `StockServiceIT`, `StockApiIT`, `StockDatabaseGuardIT`, `scenario.*` | Entrée, sortie, stock insuffisant, deux dépôts, transfert, vente, réception, retour, annulation, inventaire, rollback, concurrence |
| Ventes | `SaleApiIT`, `SaleTransactionIT`, `SalePricingTest`, `CustomerReturnApiIT`, `CustomerReturnTransactionIT` | Prix serveur, crédit, annulation, retours plafonnés au vendu |
| Achats | `PurchaseApiIT`, `PurchaseReceiptIT`, `PurchaseOrderTest`, `GoodsReceiptServiceTest` | Réception partielle, jamais au-delà du commandé, prix d'achat mis à jour |
| Inventaires | `InventoryApiIT`, `InventoryTransactionIT`, `InventorySessionTest` | Workflow, périmètre figé, écart mesuré appliqué en une fois |
| Caisse | `CashApiIT`, `CashTransactionIT`, `CashSummaryTest` | Caisse individuelle, sorties plafonnées, encaissement unique, contre-passation, visibilité superviseur |

## Scénarios transverses (`com.geststock.scenario`)

| Test | Règle |
|---|---|
| `StockLifecycleIT` | Un produit traverse réception partielle, transfert, vente, retour, annulation, perte, refus pour stock insuffisant et inventaire sur deux dépôts : à chaque étape le niveau attendu, à la fin niveau = somme des mouvements et chaque mouvement de pièce désigne sa pièce. |
| `CrossModuleConcurrencyIT` | Ventes, transferts et pertes simultanés : exactement le stock disponible part ; annulation et retour simultanés d'une même vente : un seul l'emporte ; réceptions et ventes simultanées : aucun mouvement perdu ; validation d'inventaire pendant des ventes : écart appliqué en entier ou pas du tout, jamais de stock négatif. |
| `CrossModuleRollbackIT` | Retour ou annulation dont le remboursement dépasse le tiroir : remise en stock, pièce, écriture de caisse et audit annulés ensemble ; remboursement par défaut avec le moyen de la vente. |

Les tests de concurrence n'acceptent que des refus métier (`STOCK_INSUFFICIENT`,
`SALE_HAS_RETURNS`…) : un interblocage ou une erreur technique les fait échouer.
