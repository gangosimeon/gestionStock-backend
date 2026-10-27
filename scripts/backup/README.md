# Sauvegardes GestStock

Sauvegarde chiffrée de toutes les bases : base historique, base plateforme et une base par entreprise.
Le workflow GitHub Actions [`backup`](../../.github/workflows/backup.yml) la lance chaque nuit à 02:30 UTC,
et à la demande (Actions > backup > Run workflow).

À chaque exécution :
1. `backup-databases.sh` exporte chaque base (`pg_dump`, format custom). Les bases d'entreprise sont lues
   dans `platform.company_database` et exportées avec le compte provisionneur, seul autorisé à s'y connecter.
2. Les exports sont réunis dans une archive chiffrée en AES-256 (`gpg --symmetric`), avec un manifeste
   (empreintes SHA-256).
3. `verify-backup.sh` déchiffre l'archive, contrôle les empreintes et restaure chaque base dans un
   PostgreSQL 18 jetable, puis vérifie que les tables sont présentes. **Une sauvegarde non restaurable
   fait échouer le workflow** (e-mail de GitHub).
4. L'archive est conservée 30 jours comme artefact privé du workflow.

## Mise en place (une fois)

Dans le dépôt GitHub : Settings > Secrets and variables > Actions > New repository secret.

| Secret | Valeur |
|---|---|
| `GESTSTOCK_BACKUP_ADMIN_URL` | `postgresql://neondb_owner:<mot-de-passe>@<hôte-neon>` (sans nom de base) |
| `GESTSTOCK_BACKUP_PROVISIONER_URL` | `postgresql://geststock_provisioner:<mot-de-passe>@<hôte-neon>` |
| `GESTSTOCK_BACKUP_PASSPHRASE` | Phrase secrète longue (au moins 32 caractères) |

- Hôte Neon direct, sans `-pooler`. Un mot de passe contenant des caractères spéciaux doit être encodé
  pour une URL (`@` → `%40`, `:` → `%3A`, `/` → `%2F`).
- **Conserver la phrase secrète hors de GitHub** (gestionnaire de mots de passe), avec la clé
  `GESTSTOCK_TENANT_CREDENTIALS_KEY` : sans elle, une sauvegarde est illisible.
- Les workflows planifiés ne tournent que depuis la branche par défaut (`main`).

Premier essai : Actions > backup > Run workflow, puis vérifier l'étape « Test de restauration ».

## Restaurer

1. Télécharger l'artefact `geststock-backup-<n>` (Actions > exécution > Artifacts) et le décompresser.
2. Déchiffrer (Linux, macOS, ou Git Bash sous Windows) :
   ```bash
   gpg --decrypt geststock-backup-AAAAMMJJ-HHMM.tar.gpg | tar -xf -
   ```
   La phrase secrète est demandée. Le dossier obtenu contient un fichier `.dump` par base et `MANIFEST.txt`.
3. Restaurer une base, par exemple celle d'une entreprise, après l'avoir suspendue dans l'espace Super Admin :
   ```bash
   pg_restore --clean --if-exists --no-owner --no-acl \
     -d "postgresql://geststock_provisioner:<mot-de-passe>@<hôte>/geststock_company_0002?sslmode=require" \
     geststock_company_0002.dump
   ```
   Une base d'entreprise se restaure avec le compte provisionneur, propriétaire de ses objets. Réactiver
   ensuite l'entreprise ; ses droits sont réappliqués par la mise à jour des bases (espace Super Admin).
4. Pour vérifier une archive sans toucher à la production : `verify-backup.sh <archive>` avec
   `GESTSTOCK_VERIFY_URL` pointant vers un PostgreSQL de test.
