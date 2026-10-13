# Paiements fournisseurs

Ce document décrit le module des paiements fournisseurs : migration V025, package `com.geststock.purchase`.

Un paiement règle une dette d'achat. Il est toujours imputé sur des commandes, ce qui donne un seul solde : le reste à payer d'un fournisseur est la somme des restes à payer de ses commandes.

## Endpoints

| Méthode | URL | Permission | Rôle |
|---|---|---|---|
| GET | `/api/suppliers/{id}/payments` | `purchase:read` | Paiements du fournisseur, du plus récent au plus ancien, avec leur répartition par commande. Recherche `q` : n°, référence externe, note. |
| POST | `/api/suppliers/{id}/payments` | `purchase:pay` | Payer un fournisseur. La commande est facultative. Réponse : 201 `SupplierPayment`. |
| GET | `/api/suppliers/{id}/account` | `purchase:read` | Compte du fournisseur, calculé par le serveur. |
| POST | `/api/purchases/orders/{id}/pay` | `purchase:pay` | Payer une commande. Même règle que ci-dessus. Réponse : 200 avec la commande et son `paidAmount` à jour. |

Corps d'un paiement (`SupplierPaymentRequest`) :

```json
{"amount": 150000, "paymentDate": "2026-10-10", "paymentMethod": "BANK_TRANSFER",
 "orderId": "…", "externalReference": "VIR-2026-1042", "note": "…"}
```

- `paymentDateIso` est accepté comme synonyme de `paymentDate`. Si la date est absente, le serveur prend celle du jour.
- `paymentMethod` vaut `CASH` par défaut.

## Règles

1. **Montant dû d'une commande** (`PurchaseOrder.payableAmount()`) :
   - en cours (`ORDERED`), reçue en partie (`PARTIALLY_RECEIVED`) ou reçue (`RECEIVED`) : le total commandé, ce qui permet de verser un acompte ;
   - soldée (`CLOSED`) : la valeur reçue ;
   - annulée (`CANCELLED`) : rien.
2. **Reste à payer** = dû − payé. Il n'y a jamais de trop-payé :
   - sur une commande, un montant supérieur au reste est refusé (`PURCHASE_ORDER_PAYMENT_EXCEEDS_REMAINING`, 422) ;
   - sans commande, un montant supérieur au reste dû au fournisseur est refusé (`SUPPLIER_PAYMENT_EXCEEDS_BALANCE`, 422). Le détail de l'erreur indique le reste.
3. **Paiement sans commande** : il est imputé sur les commandes les plus anciennes qui ont un reste à payer (ordre de création), parmi celles des dépôts visibles par l'utilisateur.
4. **Espèces** :
   - le montant sort de la caisse ouverte de l'utilisateur, par une écriture `SUPPLIER_PAYMENT` (sortie) ;
   - il faut une caisse ouverte (`NO_OPEN_CASH_SESSION`, 409) et assez d'espèces (`CASH_INSUFFICIENT`, 422) ;
   - les deux contrôles ont lieu avant toute écriture, et la sortie elle-même vérifie à nouveau les espèces sous verrou.

   Un virement ou un paiement mobile money ne passe pas par la caisse.
5. **Date** : la date de paiement ne peut pas être dans le futur (`SUPPLIER_PAYMENT_DATE_IN_FUTURE`, 400). La commande désignée doit appartenir au fournisseur (`SUPPLIER_PAYMENT_ORDER_MISMATCH`, 400).
6. **Annulation et solde des commandes** : une commande payée ne peut être ni annulée ni soldée si ses paiements dépassent la valeur reçue (`PURCHASE_ORDER_PREPAID`, 409).
7. **Ajout seul** : paiements et imputations ne se modifient ni ne se suppriment. L'annulation d'un paiement saisi par erreur reste à concevoir.

## Compte fournisseur (`SupplierAccount`)

| Champ | Calcul |
|---|---|
| `orderedAmount` | Total commandé, hors commandes annulées |
| `receivedAmount` | Valeur des marchandises reçues |
| `payableAmount` | Somme des montants dus (règle 1) |
| `paidAmount` | Montant réglé |
| `remainingToPay` | `payableAmount − paidAmount` |
| `advanceAmount` | Acomptes : payé au-delà de la valeur reçue |
| `ordersToPay` | Nombre de commandes avec un reste à payer |

Le compte ne porte que sur les dépôts visibles par l'utilisateur.

## Concurrence et intégrité

- **Verrous** : les commandes sont verrouillées (`SELECT … FOR UPDATE`) toujours de la plus ancienne à la plus récente. Deux paiements simultanés ne peuvent donc pas dépasser le dû (`SupplierPaymentConcurrencyIT`).
- **Contrôles différés** : la base vérifie au COMMIT trois cohérences, par contraintes différées :
  - le paiement est entièrement imputé, et seulement sur des commandes de son fournisseur ;
  - `purchase_order.paid_amount` est égal à la somme des imputations de la commande ;
  - un paiement en espèces a son écriture de caisse, et lui seul.
- **Contraintes CHECK** : `paid_amount ≤ total_amount`, et `paid_amount ≤ received_amount` pour une commande soldée ou annulée.
- **Audit** : action `PAY`, type d'entité `SUPPLIER_PAYMENT`, module `PURCHASE`.
- **Rollback** : `db/rollback/U025__supplier_payment.sql`. Prérequis : aucune écriture de caisse `SUPPLIER_PAYMENT`.
