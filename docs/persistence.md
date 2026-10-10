# Stratégie de persistance — GestStock

Ce document est la référence pour toute évolution du schéma. Toute migration qui s'en
écarte doit le justifier dans son en-tête.

## 1. Principes

| Règle | Mise en œuvre |
|---|---|
| PostgreSQL est la seule base supportée | Pas de H2, y compris en test (Testcontainers ou base PostgreSQL dédiée) |
| **Flyway est l'unique source du schéma** | `spring.jpa.hibernate.ddl-auto=validate` dans tous les profils ; un test (`PersistenceConfigurationTest`) refuse toute valeur `create`, `create-drop` ou `update` |
| Toute évolution passe par une migration versionnée | `src/main/resources/db/migration/V{NNN}__{module}_{sujet}.sql` |
| Une migration appliquée n'est **jamais** modifiée | Correction = nouvelle migration. `validate-on-migrate=true` détecte toute altération (checksum) |
| `flyway clean` interdit | `clean-disabled=true` dans tous les profils |
| Hibernate vérifie, ne crée rien | Une divergence entité ↔ schéma empêche le démarrage (et fait échouer les tests) |

## 2. Identifiants : UUID plutôt que BIGINT

### Analyse

| Critère | BIGINT (séquence) | UUID |
|---|---|---|
| Taille (PK + chaque FK + index) | 8 octets | 16 octets |
| Localité d'insertion dans l'index B-tree | Excellente | Mauvaise en v4 aléatoire, **bonne en v7** (préfixe horodaté) |
| Génération | Aller-retour base (séquence) ou `IDENTITY` | Côté application, sans aller-retour, avant l'`INSERT` |
| Énumération par un tiers (`/api/sales/124` → `/125`) | Facile : révèle les volumes, facilite les accès indus | Impossible |
| Fusion de données entre sites ou bases (multi-dépôt, import, futur multi-entreprise) | Collisions à gérer | Aucune collision |
| Contrat front existant | Le front manipule des identifiants **chaînes opaques** (`p_1`, `wh_1`) | Chaîne opaque : compatible |
| Lisibilité humaine | Bonne | Mauvaise : compensée par des **numéros métier** séparés (`VTE-2026-000123`), générés par séquence |

### Décision

**UUID v7** pour toutes les clés primaires :

- générés par l'application (`@UuidGenerator(style = VERSION_7)` dans `BaseEntity`) ;
- `DEFAULT gen_random_uuid()` en base, uniquement pour les lignes insérées en SQL (données de référence des migrations) ;
- type PostgreSQL `uuid` (16 octets binaires, jamais `varchar`).

Le surcoût de stockage est négligeable à l'échelle visée (quelques milliers de références, quelques centaines de milliers de mouvements). Les numéros lisibles par l'utilisateur sont des colonnes métier distinctes, uniques, alimentées par des séquences PostgreSQL (jamais `MAX()+1`).

## 3. Conventions de nommage

| Élément | Convention | Exemple |
|---|---|---|
| Table | `snake_case`, **singulier** | `warehouse`, `sale_line` |
| Mot réservé SQL | Préfixe métier | `app_user` (pas `user`) |
| Table de jointure | `<table_a>_<table_b>` | `user_role`, `role_permission` |
| Colonne | `snake_case` | `full_name`, `default_warehouse_id` |
| Clé primaire | `id` | — |
| Clé étrangère | `<entité référencée>_id`, ou rôle explicite | `warehouse_id`, `default_warehouse_id` |
| Booléen | Adjectif ou participe, sans `is_` | `active`, `built_in`, `must_change_password` |
| Horodatage | Suffixe `_at` | `created_at`, `locked_until` (exception sémantique) |
| Date métier | Suffixe `_date` | `payment_date` |
| Auteur | Suffixe `_by` (UUID utilisateur) | `created_by` |
| Contraintes et index | Nommés **explicitement** (voir §5) | `uk_app_user_username` |

## 4. Types et colonnes standard

| Donnée | Type PostgreSQL | Type Java |
|---|---|---|
| Identifiant | `uuid` | `UUID` |
| Instant (date + heure) | `timestamptz` (stocké en UTC) | `Instant` |
| Date métier sans heure | `date` | `LocalDate` |
| Montant | `numeric(14,2)` | `BigDecimal` |
| Quantité | `integer` | `int` |
| Texte court | `varchar(n)` (longueur bornée) | `String` |
| Texte long | `text` | `String` |
| Énumération | `varchar(n)` + contrainte `CHECK` | `enum` + `@Enumerated(STRING)` |
| Données semi-structurées | `jsonb` | `String` + `@JdbcTypeCode(SqlTypes.JSON)` |
| Haché (SHA-256 hex) | `varchar(64)` (pas `char` : incompatible avec la validation Hibernate) | `String` |

**Colonnes standard des tables modifiables** (fournies par `AuditableEntity`) :

| Colonne | Type | Rôle |
|---|---|---|
| `id` | `uuid NOT NULL` | Clé primaire |
| `version` | `bigint NOT NULL DEFAULT 0` | Verrouillage optimiste (`@Version`), conflit → HTTP 409 |
| `created_at` | `timestamptz NOT NULL DEFAULT now()` | Création |
| `created_by` | `uuid NULL` | Auteur ; `NULL` = système ou migration |
| `updated_at` | `timestamptz NOT NULL DEFAULT now()` | Dernière modification |
| `updated_by` | `uuid NULL` | Auteur de la dernière modification |

`created_by` et `updated_by` ne portent **pas** de clé étrangère : un utilisateur désactivé, voire supprimé, ne doit jamais bloquer l'historique.

**Tables immuables** (journaux : `audit_log`, puis `stock_movement`, grands livres) : pas de `version`, pas de `updated_*`. Un trigger refuse `UPDATE`, `DELETE` et `TRUNCATE`.

## 5. Contraintes, index, clés étrangères

| Élément | Convention | Exemple |
|---|---|---|
| Clé primaire | `pk_<table>` | `pk_warehouse` |
| Clé étrangère | `fk_<table>_<colonne sans _id>` | `fk_app_user_default_warehouse` |
| Unicité | `uk_<table>_<colonnes>` (contrainte ou index unique) | `uk_role_code` |
| Unicité insensible à la casse | Index unique sur `lower(col)`, même préfixe `uk_` | `uk_app_user_username` |
| Unicité partielle | Index unique avec `WHERE` | `uk_app_user_email` (`WHERE email IS NOT NULL`) |
| Vérification | `ck_<table>_<règle>` | `ck_app_user_failed_attempts` |
| Index | `ix_<table>_<colonnes>` | `ix_user_role_role_id` |

Règles :

1. **Toute colonne de clé étrangère est indexée** (sauf si elle est le premier membre d'une clé primaire ou d'un index existant). Sans index, une suppression ou mise à jour côté parent parcourt toute la table enfant. Vérifié par `SchemaConventionsIT`.
2. **`ON DELETE`** :
   - `CASCADE` uniquement pour les tables de jointure côté propriétaire (`user_role.user_id`) ;
   - `RESTRICT` partout ailleurs : une donnée référencée ne disparaît jamais silencieusement.
3. **Les noms `uk_*` sont des contrats** : `GlobalExceptionHandler` traduit une violation d'unicité en message métier via `ConstraintErrorMapping` (ex. `uk_app_user_username` → « Nom d'utilisateur déjà utilisé »). Renommer une contrainte impose de mettre à jour ce mapping.
4. Les invariants simples sont **doublés en base** (`CHECK`) même s'ils sont validés en Java : la base reste cohérente quelle que soit la source de l'écriture.
5. **Énumérations** :
   - fermées (statuts métier) : `CHECK (col IN (...))` ;
   - ouvertes (catalogues techniques extensibles comme les actions d'audit) : `CHECK` de format (`^[A-Z][A-Z0-9_]*$`), pour ne pas exiger une migration à chaque nouvelle valeur.
6. Tous les noms de contraintes et d'index respectent ces préfixes : vérifié par `SchemaConventionsIT`.

## 6. Suppression : désactivation plutôt que suppression

| Catégorie | Stratégie |
|---|---|
| Référentiels (utilisateurs, dépôts, puis produits, clients, fournisseurs, catégories, marques) | **Soft delete par `active boolean NOT NULL DEFAULT true`**. Le `DELETE` HTTP du front désactive. Une suppression physique n'est possible que si aucune pièce ne référence la ligne (les FK `RESTRICT` le garantissent). |
| Pièces commerciales (ventes, commandes, inventaires, sessions de caisse) | **Jamais supprimées** : cycle de vie par `status` (annulation motivée). |
| Journaux (audit, mouvements de stock, grands livres) | **Immuables** : trigger `fn_prevent_modification`. |
| Tables de jointure | Suppression physique (pas d'historique propre ; les changements sont tracés dans `audit_log`). |

Pourquoi `active` et pas `deleted_at` : le front manipule déjà la notion « actif / inactif » (`User.isActive`). Un élément désactivé reste visible dans l'historique et peut être réactivé. Il n'y a **pas** de filtre global Hibernate (`@SQLRestriction`) : chaque requête choisit explicitement d'inclure ou non les inactifs, pour éviter les données « invisibles » dans les rapports.

## 7. Organisation des migrations

| Emplacement | Contenu | Profils |
|---|---|---|
| `db/migration/V{NNN}__*.sql` | Schéma et **données de référence indispensables en production** (rôles, permissions, dépôt principal) | Tous |
| `db/seed/dev/R__dev_*.sql` | Données de démonstration, **idempotentes** (`ON CONFLICT DO NOTHING`) | `dev` uniquement |
| `db/rollback/U{NNN}__*.sql` | Script d'annulation manuelle de la migration `V{NNN}` correspondante | Jamais exécuté par Flyway |

Les seeds de démonstration sont des migrations **répétables** (`R__`) : elles s'exécutent après toutes les versionnées. On peut donc ajouter une migration `V007` plus tard sans conflit d'ordre avec les données de démo.

### Migrations existantes

| Version | Contenu |
|---|---|
| `V001__core_functions.sql` | Fonction `fn_prevent_modification()` (immuabilité des journaux) |
| `V002__security_roles_permissions.sql` | `role`, `permission`, `role_permission` |
| `V003__security_reference_data.sql` | 3 rôles système, 41 permissions, matrice rôle → permissions |
| `V004__warehouse.sql` | `warehouse` + dépôt principal `MAG-01` |
| `V005__user.sql` | `app_user`, `user_role`, `user_warehouse` |
| `V006__audit_log.sql` | `audit_log` immuable + index de consultation |
| `V007__user_names_and_email_check.sql` | `app_user` : `full_name` remplacé par `first_name` (facultatif) + `last_name` (obligatoire), contrôle du format d'email |
| `V008__security_refresh_token.sql` | `refresh_token` (haché SHA-256, rotation, révocation) et `app_user.credentials_changed_at` |
| `V009__security_fine_grained_permissions.sql` | Catalogue affiné (45 permissions), `role:manage`, report de la matrice sans perte de droits |
| `V010__catalog_category_brand.sql` | `category` (5 catégories optiques de référence), `brand` |
| `V011__catalog_product.sql` | `product` — **sans aucune quantité de stock** (le stock relève du module stock) |
| `V012__warehouse_main_flag.sql` | `warehouse.main` : dépôt principal unique (`uk_warehouse_main`, index partiel) et toujours actif (`ck_warehouse_main_active`) ; `MAG-01` désigné |
| `V013__stock.sql` | `stock_movement` (journal en ajout seul) et `stock_level` (source de vérité, clé dépôt × produit, trigger `fn_stock_level_guard` : variation uniquement par mouvement) |
| `V014__supplier.sql` | `supplier` (code unique généré par `seq_supplier_code`, nom unique sans casse, délais 0–365 j) ; clé étrangère `fk_product_default_supplier` ; `supplier:read` attribué au rôle `CAISSIER` |
| `V015__purchase.sql` | `purchase_order` (+ `_line`), `goods_receipt` (+ `_line`, ajout seul) ; numéros par séquences ; `fn_goods_receipt_line_guard` (ligne reçue = mouvement `RECEIPT` exact) ; contraintes **différées** `fn_purchase_receipt_consistency` (quantité reçue = lignes reçues, vérifiée au COMMIT) |
| `V016__customer.sql` | `customer` (code unique `seq_customer_code`, type, catégorie de prix, plafond de crédit, délai de paiement) ; `customer_account_entry` (compte client en ajout seul, sens imposé par le type, solde jamais négatif, une imputation par pièce) ; permission `customer:credit` (`ADMIN`, `GESTIONNAIRE`) |
| `V017__sale.sql` | `sale` (montants figés par `fn_sale_guard`, reste dû ⇒ client) et `sale_line` (ajout seul, `fn_sale_line_guard` : ligne = sortie de stock exacte) ; contrainte **différée** `fn_sale_consistency` (totaux, créance, annulation) ; séquence `seq_sale_number` |
| `V018__cash.sql` | `cash_session` (une caisse ouverte par utilisateur, `uk_cash_session_open_cashier` ; clôture figée par `fn_cash_session_guard`) et `cash_entry` (ajout seul, `fn_cash_entry_guard` : session ouverte et titulaire) ; contraintes **différées** `fn_sale_cash_consistency` (vente payée ⇒ encaissement) et `fn_customer_payment_consistency` (règlement ⇔ encaissement) |
| `V019__inventory.sql` | `inventory_session` (un inventaire ouvert par dépôt, transitions et état terminé figé par `fn_inventory_session_guard`) et `inventory_line` (`fn_inventory_line_guard`) ; contrainte **différée** `fn_inventory_consistency` (écarts appliqués exactement par des mouvements `INVENTORY`) |
| `V020__customer_return.sql` | `customer_return` et `customer_return_line` (en ajout seul ; `fn_customer_return_line_guard`), contrainte **différée** `fn_customer_return_consistency` (cumul ≤ vendu, remboursement ou avoir exact) et `fn_sale_return_guard` (vente avec retours non annulable) ; `cash_entry` : types `RETURN_REFUND`, `CORRECTION_IN`/`CORRECTION_OUT` et `reversal_of_id` (contre-passation exacte, `fn_cash_correction_guard`) |
| `V021__alert_setting.sql` | `alert_setting` : paramètres des alertes calculées (ligne unique, `uk_alert_setting_singleton`), permission `alert:configure` (ADMIN, GESTIONNAIRE) ; index partiel `ix_purchase_order_open_expected` (commandes ouvertes par date prévue) |

L'administrateur initial n'est **pas** créé par migration : un mot de passe (même haché) ne doit pas être versionné. Il sera créé au premier démarrage par le module sécurité, à partir de variables d'environnement.

## 8. Rollback

Flyway Community ne gère pas l'annulation automatique (`undo`). La stratégie retenue est la suivante.

### 8.1 En production : on avance, on ne recule pas

1. **Avant toute mise en production** : sauvegarde complète (`pg_dump -Fc`). C'est le seul vrai retour arrière en cas d'incident grave.
2. **Erreur dans une migration déjà appliquée** : nouvelle migration corrective (*roll-forward*), jamais de modification du fichier existant.
3. **Migration en échec à mi-parcours** : PostgreSQL exécute chaque migration Flyway dans une transaction (DDL transactionnel). Une migration qui échoue est **entièrement annulée** ; Flyway la marque en échec. On corrige le fichier (non appliqué) puis on relance.
4. **Migrations rendues compatibles avec la version précédente de l'application** dès que possible (ajout de colonne nullable, puis remplissage, puis contrainte), pour permettre un retour à l'application N-1 sans toucher au schéma.

### 8.2 Annulation manuelle (dev, recette, incident maîtrisé)

Chaque migration versionnée `V{NNN}` a un script `db/rollback/U{NNN}__*.sql` qui :

- défait exactement la migration ;
- supprime sa ligne dans `flyway_schema_history`.

Ces scripts sont **testés** (`MigrationRollbackIT`) : application de toutes les migrations sur un schéma isolé, annulation de toutes dans l'ordre inverse, vérification que le schéma est vide, puis ré-application complète.

Procédure (exemple : annuler `V006`) :

```bash
pg_dump -Fc -d geststock -f avant-rollback.dump           # 1. sauvegarde
# 2. arrêter l'application
psql -d geststock -v ON_ERROR_STOP=1 -1 \
     -f src/main/resources/db/rollback/U006__audit_log.sql  # 3. annulation (transaction unique)
# 4. redéployer la version de l'application correspondant à V005
```

Annuler plusieurs versions : exécuter les scripts **du plus récent au plus ancien**. Une annulation détruit les données des tables concernées : elle n'est jamais une opération de routine en production.
