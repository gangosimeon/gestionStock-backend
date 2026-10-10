# Tableau de bord : indicateurs et règles de calcul

Tous les indicateurs sont **calculés à chaque appel sur PostgreSQL** (`DashboardService`,
`DashboardQueries`), à partir des pièces enregistrées : ventes, retours, réceptions,
commandes, niveaux et mouvements de stock. Aucune valeur n'est simulée, estimée ou mise en
cache ; un graphique sans donnée affiche des zéros, jamais une tendance inventée.

## Périmètre commun

| Règle | Détail |
|---|---|
| Dépôt | Dépôt courant (`X-Warehouse-Id`) ; tous les dépôts avec `allWarehouses=true` (exige `warehouse:all`). |
| Jour | Découpage dans le fuseau de l'entreprise, `geststock.dashboard.time-zone` (défaut `Africa/Abidjan`, UTC sans heure d'été). |
| Période | Dates `AAAA-MM-JJ` incluses (`from`, `to`) ou `days` derniers jours, aujourd'hui compris ; 366 jours au plus. |
| Ventes prises en compte | Statut `COMPLETED`, datées de leur création. Une vente annulée sort des chiffres, y compris pour les jours passés. |
| Retours | Comptés **le jour du retour** (vue des flux), même si la vente date d'une autre période. |
| Montants | FCFA, 2 décimales, remises déduites ; pas de TVA (non gérée à ce stade). |
| Droit | `report:read` (ADMIN, GESTIONNAIRE). |

## Indicateurs de vente (`sales`)

| Indicateur | Calcul |
|---|---|
| `count` | Nombre de ventes validées de la période. |
| `grossSales` (CA brut) | Σ `sale.total` des ventes validées (prix serveur, remises déduites). |
| `returns` | Σ `customer_return.total_amount` des retours de la période (remboursés ou en avoir), au prix net de la vente. |
| `netSales` (**chiffre d'affaires net**) | `grossSales − returns`. Carte « Ventes du jour » du front (`todaySalesTotal`). |
| `costOfSales` | Σ `sale.cost_total` − coût des articles **revendables** retournés (Σ quantité × `unit_cost` des lignes `RESELLABLE`). Un article défectueux retourné reste une perte : son coût n'est pas déduit. |
| `grossProfit` (**marge brute**) | `netSales − costOfSales`. Carte « Bénéfice du jour » (`todayProfit`). |
| `marginRate` | `grossProfit / netSales × 100`, 1 décimale ; absent si `netSales ≤ 0`. |
| `averageBasket` | `grossSales / count` ; absent sans vente. |
| `cancelledCount` | Ventes annulées pendant la période (`cancelled_at`), pour information. |

**Fiabilité de la marge.** Le coût d'une ligne de vente est figé au moment de la vente
(`sale_line.unit_cost` = prix d'achat de référence du produit, c.-à-d. le dernier coût reçu).
La marge est donc fiable au sens « prix de vente − dernier prix d'achat connu » ; ce n'est pas
un coût moyen pondéré (CUMP, non géré). Les dépenses ne sont pas déduites : c'est une marge
brute, pas un bénéfice net.

## Achats (`purchases`)

| Indicateur | Calcul |
|---|---|
| `receiptsCount`, `receivedAmount` | Réceptions dont la date de réception est dans la période ; Σ `goods_receipt.total_amount` (quantité reçue × coût unitaire de la commande). |
| `ordersCount`, `orderedAmount` | Commandes créées dans la période, hors annulées ; Σ `purchase_order.total_amount`. |

## Stock (`stock`, à l'instant de l'appel)

| Indicateur | Calcul |
|---|---|
| `units` | Σ `stock_level.quantity`. Carte « Stock total » (`stockTotal`). |
| `value` | Σ quantité × `product.purchase_price` (dernier prix d'achat), quantités positives. |
| `productsInStock` | Produits dont la quantité est positive. |
| `outOfStock`, `belowMinimum`, `lowStock` | Nombre d'alertes `OUT_OF_STOCK`, `BELOW_MINIMUM`, `LOW_STOCK` : mêmes règles que le module alertes (seuil du produit, seuil par défaut, marge configurée). `null` si l'utilisateur n'a pas `stock:read`. |

## Séries

| Série | Calcul |
|---|---|
| `salesByDay` (« Ventes par jour ») | Un point par jour de la période, **jours sans vente compris (0)** : `count`, `grossSales`, `returns`, `total` = CA net du jour, `profit` = marge brute du jour, avec les règles ci-dessus. |
| `topProducts` (« Top produits ») | Produits vendus pendant la période : quantité vendue − quantité retournée, `total` = Σ `line_total` − Σ montants retournés, `profit` = `total` − (coût vendu − coût des revendables retournés). Tri par `total` décroissant, puis quantité, puis référence ; 8 par défaut (50 au plus) sur 30 jours. |
| `stockEvolution` (« Évolution du stock ») | Stock **en fin de journée**, reconstitué depuis le journal des mouvements : stock actuel − Σ des mouvements postérieurs à la fin du jour. Exact, puisque chaque niveau est la somme de ses mouvements (invariant garanti par la base). Le point d'aujourd'hui est le stock actuel. |

Remplace l'ancienne courbe du front, qui était **fictive** (stock actuel × (1 − 0,02·i)).

## Endpoints

| Endpoint | Défaut | Contenu |
|---|---|---|
| `GET /api/dashboard` | — | Forme du `DashboardVm` du front : `kpis` du jour, `salesByDay` 14 jours, `topProducts` 8 sur 30 jours, `stockEvolution` 7 jours, `today` (indicateurs détaillés du jour). |
| `GET /api/dashboard/summary` | aujourd'hui | `sales`, `purchases`, `stock` d'une période (`from`, `to`). |
| `GET /api/dashboard/sales-by-day` | 14 jours | `from`/`to` ou `days`. |
| `GET /api/dashboard/top-products` | 30 jours, 8 | `from`/`to` ou `days`, `limit`. |
| `GET /api/dashboard/stock-evolution` | 7 jours | `from`/`to` ou `days` ; une date future est ramenée à aujourd'hui. |

## Performances

- Chaque série est **une requête SQL agrégée** (pas de chargement de pièces en mémoire, au
  contraire de l'ancien front qui téléchargeait toutes les ventes) ; la série des ventes est
  aussi la source des indicateurs de la période, ce qui garantit leur cohérence.
- Bornes de dates converties en instants avant la requête : les index sur `created_at` /
  `received_at` sont utilisables (pas de fonction sur la colonne filtrée).
- Index (V017, V020, V015, V023) :
  - `ix_sale_warehouse_id_created_at` (un dépôt) et `ix_sale_completed_created_at`
    (tous les dépôts, partiel `COMPLETED`, `INCLUDE (warehouse_id, total, cost_total)` :
    parcours d'index seul) ;
  - `ix_customer_return_warehouse_id_created_at`, `ix_customer_return_created_at` ;
  - `ix_goods_receipt_warehouse_id_received_at`, `ix_goods_receipt_received_at` ;
  - `ix_stock_movement_warehouse_id_created_at`, `ix_stock_movement_created_at`
    (`INCLUDE (warehouse_id, quantity)`).
- Évolution du stock : une seule requête groupée par jour sur les mouvements depuis le début
  de la période, puis cumul inverse en mémoire (N points, sans requête par jour).
- Un appel = une transaction en lecture seule : instantané cohérent entre ventes, retours et
  stock.
