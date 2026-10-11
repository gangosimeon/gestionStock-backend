# Rendez-vous

Agenda des rendez-vous clients d'un dépôt. Migration `V028__appointment.sql`, module
`appointment`, endpoints `/api/appointments`.

## Règles

1. **Rattachement** : un rendez-vous appartient au dépôt courant (`X-Warehouse-Id`) au moment de sa prise.
2. **Personne attendue** : un téléphone est toujours connu. Sinon le rendez-vous est refusé avec `APPOINTMENT_CONTACT_REQUIRED` (400).
   - **Client du fichier** (`customerId`) :
     - le client doit être actif (`CUSTOMER_INACTIVE` sinon) ;
     - son nom est repris de sa fiche, et le nom envoyé est ignoré ;
     - son téléphone est repris de sa fiche si `phone` est vide.
   - **Prospect** (sans `customerId`) : nom et téléphone obligatoires.
3. **Horaire** :
   - `dateTime` est une heure locale du dépôt (`AAAA-MM-JJTHH:mm`), interprétée dans le fuseau de l'entreprise (`geststock.dashboard.time-zone`) ;
   - un rendez-vous se planifie dans le futur (`APPOINTMENT_IN_PAST`, 400) ;
   - la réponse renvoie `dateTime` (heure locale) et `scheduledAt` (instant UTC).
4. **Un créneau, un rendez-vous** :
   - un seul rendez-vous planifié par dépôt et par horaire exact (`APPOINTMENT_SLOT_TAKEN`, 409) ;
   - la règle est garantie par l'index unique `uk_appointment_slot`, même en cas de saisies simultanées ;
   - un rendez-vous clos libère son créneau.
5. **Statuts** :

   | Statut | Sens | Quand |
   |---|---|---|
   | `SCHEDULED` | planifié | à la création ; seul statut modifiable |
   | `COMPLETED` | honoré | à partir du jour du rendez-vous (`APPOINTMENT_NOT_YET_DUE` avant) |
   | `NO_SHOW` | absent | à partir du jour du rendez-vous |
   | `CANCELLED` | annulé | à tout moment, motif obligatoire (`APPOINTMENT_CANCELLATION_REASON_REQUIRED`) |

   - Un rendez-vous ne se clôt qu'une fois. Une fois clos, il ne se modifie plus (`APPOINTMENT_CLOSED`, 409, et trigger `fn_appointment_guard` en base).
   - La clôture enregistre sa date (`closedAt`) et son auteur (`closedByUserId`).
6. **Modification** (`PUT`) :
   - remplacement complet (personne, horaire, note), tant que le rendez-vous est planifié ;
   - un nouvel horaire doit être futur et libre ;
   - garder le même horaire reste permis, même s'il est passé dans la journée.
7. **Pas de suppression** : `DELETE` répond 405 et la base refuse la suppression. Un rendez-vous s'annule, et son historique (absences, annulations) reste consultable.

## API

| Méthode | Chemin | Permission | Rôle |
|---|---|---|---|
| GET | `/api/appointments` | `appointment:read` | Agenda du dépôt courant, du plus proche au plus lointain. Filtres : `search` (nom ; chiffres du téléphone), `status`, `dateFrom`, `dateTo` (jours locaux inclus), `customerId`. Tri : `dateTime`, `createdAt`, `customerName`, `status`. |
| GET | `/api/appointments/{id}` | `appointment:read` | Un rendez-vous d'un dépôt visible (sinon `APPOINTMENT_NOT_FOUND`). |
| POST | `/api/appointments` | `appointment:manage` | Prend un rendez-vous (201). Corps : `{customerId?, customerName?, phone?, dateTime, note?}`. |
| PUT | `/api/appointments/{id}` | `appointment:manage` | Modifie un rendez-vous planifié (même corps). |
| PATCH | `/api/appointments/{id}/status` | `appointment:manage` | Clôt le rendez-vous. Corps : `{status: COMPLETED \| NO_SHOW \| CANCELLED, reason?}`. |

## Droits par défaut

ADMIN, GESTIONNAIRE et CAISSIER : lecture et gestion.

## Audit

| Action | Type | Module |
|---|---|---|
| `CREATE`, `UPDATE` | `APPOINTMENT` | `APPOINTMENT` |
| `COMPLETE`, `NO_SHOW`, `CANCEL` | `APPOINTMENT` | `APPOINTMENT` |

Les modifications et les clôtures tracent l'état avant et l'état après.

## Rollback

Script `db/rollback/U028__appointment.sql`.
