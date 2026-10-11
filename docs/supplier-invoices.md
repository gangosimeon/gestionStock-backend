# Factures fournisseurs

Ce document décrit la facture d'une commande fournisseur : migration V026, `PurchaseOrderService.invoice`.

## Endpoint

| Méthode | URL | Permission | Rôle |
|---|---|---|---|
| POST | `/api/purchases/orders/{id}/invoice` | `purchase:pay` | Enregistrer la facture du fournisseur pour une commande. Réponse : 201 `SupplierInvoice`. |

- Corps : `{"invoiceNumber": "FA-ESS-2026-0412", "invoiceDate": "2026-10-10"}`. `invoiceDateIso` est accepté comme synonyme de `invoiceDate`.
- La facture apparaît ensuite dans chaque réponse `PurchaseOrder`, dans le champ `invoice`, qui vaut `null` tant qu'elle n'est pas enregistrée.

## Règles

1. **Une seule facture par commande.** Une fois enregistrée, elle ne se modifie plus : la base refuse toute modification par un trigger. Une deuxième demande est refusée (`PURCHASE_ORDER_ALREADY_INVOICED`, 409).
2. **Commande facturable** : seulement quand plus rien n'est attendu, c'est-à-dire une commande reçue (`RECEIVED`) ou soldée (`CLOSED`). Une commande en cours, reçue en partie ou annulée est refusée (`PURCHASE_ORDER_NOT_INVOICEABLE`, 409).
3. **Montant facturé** : la valeur reçue, calculée par le serveur. Il n'est pas saisi.
4. **Numéro** : obligatoire (50 caractères au plus) et unique par fournisseur, sans tenir compte de la casse. Un doublon est refusé (`SUPPLIER_INVOICE_NUMBER_ALREADY_USED`, 409). L'index unique `uk_purchase_order_supplier_invoice` protège aussi des doublons concurrents. Deux fournisseurs différents peuvent utiliser le même numéro.
5. **Date** : ni future, ni antérieure à la création de la commande (`SUPPLIER_INVOICE_DATE_INVALID`, 400).
6. **Audit** : action `INVOICE`, type d'entité `PURCHASE_ORDER`, module `PURCHASE`.

La facture ne change pas le reste à payer. Le montant dû d'une commande reçue ou soldée est déjà sa valeur reçue (voir [supplier-payments.md](supplier-payments.md)).

## Stockage

Le document d'architecture prévoyait une table `supplier_invoice`. Les colonnes `invoice_number`, `invoice_date`, `invoiced_amount`, `invoiced_at` et `invoiced_by` sont finalement rangées sur `purchase_order` :

- la relation est 0..1 : une commande a au plus une facture ;
- la facture est visible partout où la commande l'est, sans jointure.

La contrainte `ck_purchase_order_invoice` impose deux choses :

- une facture complète ou absente ;
- une facture uniquement sur une commande reçue ou soldée, avec un montant égal à la valeur reçue.

Rollback : `db/rollback/U026__supplier_invoice.sql`.
