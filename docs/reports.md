# Rapports : endpoints, filtres et règles de calcul

Rapports en lecture seule (`/api/reports/*`, permission `report:read`), calculés par
PostgreSQL : agrégation, tri et pagination sont faits par la base, seule la page demandée est
chargée en mémoire. Les totaux (`summary`) portent sur **tout** le filtre, pas sur la page.

## Paramètres communs

| Paramètre | Règle |
|---|---|
| `from`, `to` | Dates `AAAA-MM-JJ` incluses, fuseau de l'entreprise (`geststock.dashboard.time-zone`). Par défaut : 30 derniers jours. 10 ans au plus (366 jours pour une série journalière). |
| Dépôt | Dépôt courant (`X-Warehouse-Id`) ; `warehouseId` (dépôt accessible) ; `allWarehouses=true` (exige `warehouse:all`). |
| `page`, `size`, `sort` | Toujours paginé : page 0 et 20 lignes par défaut, 200 au plus ; tri `champ,asc|desc` parmi les champs listés (sinon 400). Ordre stable (clé de la ligne en départage). |
| `status` | Valeurs listées par rapport, insensibles à la casse ; valeur inconnue → 400. |

Réponse paginée : `{items, total, page, size, totalPages, summary}`.

## Ventes

| Endpoint | Filtres | Contenu |
|---|---|---|
| `GET /sales` | période (date de vente), dépôt, `customerId`, `userId` (vendeur), `status` (`COMPLETED`, `CANCELLED`), `productId` / `categoryId` (vente contenant ce produit / cette catégorie) | Une ligne par vente : total, coût, marge, payé, crédit, `returnedAmount` (retours de la vente), `netAmount` = total − retours. Totaux sur les ventes validées. Tri : `createdAt`, `number`, `total`, `profit`, `customerName`, `sellerName`. |
| `GET /sales/by-product` | période, dépôt, `productId`, `categoryId`, `userId`, `customerId` | Par produit : vendu (ventes validées de la période), retourné (retours de la période), net ; CA net = Σ `line_total` − Σ montants retournés ; coût = coût vendu − coût des articles **revendables** retournés ; marge et taux. Tri : `netRevenue`, `netQuantity`, `profit`, `sku`, `name`. |
| `GET /sales/by-period` | période, dépôt, `userId`, `customerId`, `groupBy` (`DAY`, `MONTH`, `YEAR`) | Série complète (0 sans vente) : nombre, CA brut, retours, CA net, coût, marge. Remplace les rapports journalier / mensuel / annuel du front (qui étaient faux). |
| `GET /sales/by-seller` | période, dépôt, `customerId` | Performance par vendeur (auteur de la vente) : retours imputés au vendeur de la vente, panier moyen. |
| `GET /sales/by-warehouse` | période, `userId`, `customerId` (comparer : `allWarehouses=true`) | Performance par dépôt. |

Règles : ventes `COMPLETED` (une vente annulée sort des chiffres) ; retours comptés **à leur
date** dans les vues agrégées, rattachés à leur vente dans la liste ; coût figé à la vente
(dernier prix d'achat), donc marge brute — ni CUMP ni dépenses.

## Achats et fournisseurs

| Endpoint | Filtres | Contenu |
|---|---|---|
| `GET /purchases` | période (date de commande), dépôt, `supplierId`, `userId`, `status` (`ORDERED`, `PARTIALLY_RECEIVED`, `RECEIVED`, `CLOSED`, `CANCELLED`), `productId`, `categoryId` | Commandé, reçu, reste à recevoir (commandes ouvertes uniquement), `overdue` = ouverte et date prévue dépassée. |
| `GET /suppliers` | période, dépôt, `supplierId`, `status` (`ACTIVE`, `INACTIVE`, `ALL`) | Commandes passées et réceptions de la période ; reste à recevoir et commandes en retard **actuels** ; dernière réception ; délai moyen constaté (création → dernière réception des commandes reçues de la période) face au délai annoncé. |

Les factures et règlements fournisseurs ne sont pas gérés : pas de rapport de dettes
fournisseurs tant que ces données n'existent pas.

## Stock

| Endpoint | Filtres | Contenu |
|---|---|---|
| `GET /stock` | dépôt, période (rotation), `productId`, `categoryId`, `status` (`ACTIVE`, `INACTIVE`, `ALL`), `stockState` (`IN_STOCK`, `OUT_OF_STOCK`, `BELOW_MINIMUM`, `DORMANT`) | Par produit : quantité (somme des niveaux du périmètre), valeur = quantité positive × dernier prix d'achat, vendu sur la période (sorties de vente − annulations − retours clients), **couverture** = quantité × jours de la période / vendu, dernière sortie (vente, transfert). `BELOW_MINIMUM` et `DORMANT` suivent les seuils des alertes. Tri : `value`, `quantity`, `soldQuantity`, `coverageDays`, `lastExitAt`, `sku`, `name`. |
| `GET /stock-movements` | période, dépôt, `productId`, `categoryId`, `userId`, `type` (`RECEIPT`, `SALE`, `LOSS`, `INVENTORY`…) | Journal des mouvements avec valeur = quantité × coût unitaire ; totaux entrées / sorties en quantité et en valeur. |
| `GET /stock-movements/by-type` | idem (sans `type`) | Nombre, quantité nette et valeur par type de mouvement. |

## Inventaires, caisse, clients

| Endpoint | Filtres | Contenu |
|---|---|---|
| `GET /inventories` | période (création), dépôt, `status`, `userId` (créateur, démarreur ou valideur), `productId`, `categoryId` | Lignes, comptées, écarts, gains et pertes en quantité, écart valorisé au coût des mouvements d'inventaire appliqués (0 tant que l'inventaire n'est pas validé). |
| `GET /cash-sessions` | période (ouverture), dépôt, `userId` (caissier), `status` (`OPEN`, `CLOSED`) | Par session : ventes en espèces (ventes − remboursements d'annulation et de retour), entrées (règlements, apports, nets des corrections), sorties, encaissements hors espèces, attendu = fond + ventes + entrées − sorties (mêmes règles que la caisse), compté, écart. |
| `GET /customers` | période, dépôt (ventes), `customerId`, `status` (`ACTIVE`, `INACTIVE`, `ALL`), `withBalance` | Créance actuelle et **part échue** (vue `v_customer_receivable`, V024, partagée avec les alertes : règlements imputés aux plus anciennes ventes, délai du client ou délai par défaut), ventes et règlements de la période, dernière vente. Tri : `balance`, `overdueAmount`, `salesAmount`, `lastSaleAt`, `name`, `code`. |

## Performances

- Une requête de comptage + une requête de page (`LIMIT`/`OFFSET`) + une requête de totaux ;
  aucune liste complète chargée.
- Bornes de dates converties en instants (index utilisables) ; index des dates de pièces par
  dépôt (V013, V015, V017, V018, V019, V020) et tous dépôts (V023).
- Sous-requêtes latérales par ligne de page (retours d'une vente, écritures d'une session,
  activité d'un client) appuyées sur les index de clé étrangère (`sale_id`, `session_id`,
  `customer_id`, `supplier_id`…).
