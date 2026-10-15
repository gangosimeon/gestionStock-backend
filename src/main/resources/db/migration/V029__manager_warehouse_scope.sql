-- =============================================================================
-- V029 — Le gestionnaire travaille dans ses dépôts affectés
--
-- V003 accordait au rôle GESTIONNAIRE « tout sauf » une liste qui ne retirait ni la vue
-- consolidée de tous les dépôts (warehouse:all) ni l'administration des dépôts
-- (warehouse:manage). Ces deux droits sont désormais réservés à l'administrateur : seul
-- warehouse:all ouvre les données de tous les dépôts.
--
-- Effet immédiat : les permissions sont relues en base à chaque requête. Un gestionnaire sans
-- dépôt affecté ne travaille plus que dans le dépôt principal ; affecter ses dépôts avant le
-- déploiement (UPDATE app_user.default_warehouse_id / INSERT user_warehouse, ou écran
-- Utilisateurs).
-- =============================================================================

DELETE FROM role_permission rp
USING role r, permission p
WHERE rp.role_id = r.id
  AND rp.permission_id = p.id
  AND r.code = 'GESTIONNAIRE'
  AND p.code IN ('warehouse:all', 'warehouse:manage');
