# Dépenses

Frais de fonctionnement d'un dépôt : loyer, salaires, transport, électricité… Migration
`V027__expense.sql`, module `expense`, endpoints `/api/expenses`.

## Règles

1. **Rattachement** : une dépense appartient au dépôt courant (`X-Warehouse-Id`) au moment de sa saisie.
2. **Contenu** :
   - catégorie fermée : `TRANSPORT`, `SALARY`, `RENT`, `UTILITIES`, `SUPPLIES`, `MAINTENANCE`, `TAXES` ou `OTHER` ;
   - libellé obligatoire (200 caractères au plus) ;
   - montant > 0 ;
   - date de la dépense : aujourd'hui par défaut, jamais dans le futur (`EXPENSE_DATE_IN_FUTURE`, 400) ;
   - référence externe et note facultatives.
3. **Mode de paiement** : espèces par défaut.
   - **Espèces** (`CASH`) : la dépense sort de la caisse ouverte de son auteur, dans le dépôt de la dépense. Elle est inscrite en caisse comme une écriture `EXPENSE` (sortie).
     - Sans caisse ouverte : refus `NO_OPEN_CASH_SESSION` (409).
     - Caisse ouverte dans un autre dépôt : refus `CASH_SESSION_OTHER_WAREHOUSE` (409).
     - Montant supérieur aux espèces attendues dans le tiroir : refus `CASH_INSUFFICIENT` (422).
     - Dans ces trois cas, rien n'est enregistré.
   - **Mobile money, virement, chèque** (`MOBILE_MONEY`, `BANK_TRANSFER`, `CHEQUE`) : la caisse n'est pas touchée. Le chèque n'existe que pour les dépenses.
4. **Pas de modification ni de suppression** : `DELETE` répond 405. La base refuse toute modification ou suppression (trigger `fn_expense_guard`), sauf l'annulation.
5. **Annulation** : `POST /api/expenses/{id}/cancel` avec `{reason}` (motif obligatoire), permission `expense:delete`.
   - La dépense passe en `CANCELLED`. Elle reste visible dans la liste, avec son motif, sa date d'annulation et son auteur, mais ne compte plus dans les totaux.
   - Une dépense ne s'annule qu'une fois : sinon `EXPENSE_ALREADY_CANCELLED` (409).
   - **Dépense payée en espèces** : l'argent revient dans la caisse ouverte de la personne qui annule, dans le dépôt de la dépense, par une écriture `EXPENSE_CANCELLATION` (entrée). Sans caisse ouverte dans ce dépôt, l'annulation est refusée : l'argent doit physiquement revenir dans un tiroir.
6. **Cohérence garantie par la base**, vérifiée au COMMIT (contrainte différée `fn_expense_cash_consistency`) :
   - une dépense en espèces a exactement une sortie de caisse du même montant ;
   - une dépense en espèces annulée a exactement un retour en caisse ;
   - une dépense hors espèces n'a aucune écriture de caisse.

## Caisse

| Écriture | Sens | Effet sur l'attendu du tiroir |
|---|---|---|
| `EXPENSE` | sortie | compte dans `totalOut` |
| `EXPENSE_CANCELLATION` | entrée | annule la sortie (déduit de `totalOut`) |

Le rapport des sessions de caisse (`/api/reports/cash-sessions`) suit la même règle.

## API

| Méthode | Chemin | Permission | Rôle |
|---|---|---|---|
| GET | `/api/expenses` | `expense:read` | Liste paginée, la plus récente d'abord (`expenseDate`, puis `createdAt`). Filtres : `dateFrom`, `dateTo` (inclus), `category`, `paymentMethod`, `status`, `search` (libellé, référence, référence externe, note), `allWarehouses` (exige `warehouse:all`). |
| GET | `/api/expenses/summary` | `expense:read` | Totaux des dépenses **payées**, sur les mêmes critères : `total`, `count`, `cashTotal` (part en espèces) et `byCategory` (du montant le plus élevé au plus faible). `cancelledCount` compte à part les dépenses annulées. |
| GET | `/api/expenses/{id}` | `expense:read` | Une dépense d'un dépôt visible (sinon `EXPENSE_NOT_FOUND`). |
| POST | `/api/expenses` | `expense:create` | Enregistre une dépense (201). Corps : `{category, label, amount, expenseDateIso?, paymentMethod?, externalReference?, note?}`. |
| POST | `/api/expenses/{id}/cancel` | `expense:delete` | Annule une dépense. Corps : `{reason}`. |

Référence d'une dépense : `DEP-AAAA-nnnnnn` (séquence `seq_expense_number`).

## Droits par défaut

- ADMIN : lecture, saisie, annulation.
- GESTIONNAIRE : lecture et saisie (pas l'annulation).
- CAISSIER : aucun accès. Une petite sortie d'espèces passe par une sortie manuelle de caisse.

La permission `expense:delete` est libellée « Annuler des dépenses (motif obligatoire) ».

## Audit

| Action | Type | Module | Contenu |
|---|---|---|---|
| `CREATE` | `EXPENSE` | `EXPENSE` | état après |
| `CANCEL` | `EXPENSE` | `EXPENSE` | état avant et après, motif |

Les écritures de caisse liées ne sont pas tracées séparément : elles portent la référence de la dépense (`sourceType = EXPENSE`).

## Rollback

Script `db/rollback/U027__expense.sql`. Prérequis : aucune écriture de caisse `EXPENSE` ni `EXPENSE_CANCELLATION`.
