-- =============================================================================
-- V012 — Dépôts : dépôt principal explicite
--
-- Un et un seul dépôt principal (index unique partiel). Il sert de dépôt par défaut
-- quand un utilisateur n'a ni dépôt attribué ni dépôt par défaut, et ne peut pas être
-- désactivé. Le dépôt MAG-01 créé par V004 devient le dépôt principal.
--
-- Annulation : db/rollback/U012__warehouse_main_flag.sql
-- =============================================================================

ALTER TABLE warehouse ADD COLUMN main boolean NOT NULL DEFAULT false;

UPDATE warehouse SET main = true WHERE code = 'MAG-01';

-- Au plus un dépôt principal ; il est forcément actif.
CREATE UNIQUE INDEX uk_warehouse_main ON warehouse (main) WHERE main;
ALTER TABLE warehouse ADD CONSTRAINT ck_warehouse_main_active CHECK (NOT main OR active);

COMMENT ON COLUMN warehouse.main IS
    'Dépôt principal (un seul, toujours actif) : dépôt par défaut des utilisateurs sans affectation.';
