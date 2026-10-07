-- =============================================================================
-- Données de démonstration (profil dev uniquement) : stock d'ouverture.
--
-- Reprend les quantités du mock front (MOCK_WAREHOUSE_STOCKS : wh_1 → MAG-01,
-- wh_2 → DEP-01). Le stock n'est jamais écrit directement : chaque quantité entre par
-- un mouvement OPENING (source DATA_IMPORT), puis le niveau désigne ce mouvement,
-- comme l'exige le trigger fn_stock_level_guard. Invariant respecté : niveau = somme
-- des mouvements.
--
-- Idempotent : ne fait rien si l'import a déjà eu lieu. Les mouvements étant
-- immuables, une modification de ce fichier n'est PAS rejouée sur une base déjà
-- alimentée (recréer la base de dev pour repartir de zéro).
--
-- Auteur des mouvements : compte technique « import.demo », inactif, sans mot de
-- passe ni rôle (il ne peut pas se connecter).
-- Généré depuis le mock ; nommé « dev_stock » pour passer après les autres seeds
-- (ordre alphabétique des migrations répétables : catalogue, dépôts, puis stock).
-- =============================================================================

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM stock_movement WHERE source_type = 'DATA_IMPORT' AND source_reference = 'DEMO-MOCK') THEN
        RETURN;
    END IF;

    INSERT INTO app_user (username, first_name, last_name, active, must_change_password)
    VALUES ('import.demo', 'Import', 'Données de démonstration', false, false)
    ON CONFLICT DO NOTHING;

    CREATE TEMP TABLE tmp_opening ON COMMIT DROP AS
    SELECT w.id AS warehouse_id, p.id AS product_id, d.qty, gen_random_uuid() AS movement_id
    FROM (VALUES
        ('MAG-01', 'MON-RB3025', 12),
        ('MAG-01', 'MON-OX8046', 8),
        ('MAG-01', 'MON-GEN-F', 15),
        ('MAG-01', 'MON-ENF-S', 10),
        ('MAG-01', 'VER-UNI-SP', 55),
        ('MAG-01', 'VER-PRO-PM', 23),
        ('MAG-01', 'VER-AR-BC', 30),
        ('MAG-01', 'VER-PHC-TR', 7),
        ('MAG-01', 'LEN-MEN-CV', 22),
        ('MAG-01', 'LEN-JOU-CV', 50),
        ('MAG-01', 'LEN-COL-AL', 18),
        ('MAG-01', 'ACC-ETU-RIG', 25),
        ('MAG-01', 'ACC-MCF-X', 38),
        ('MAG-01', 'SOL-MUL-360', 20),
        ('MAG-01', 'SOL-SAL-250', 28),
        ('MAG-01', 'MON-TF5823', 8),
        ('MAG-01', 'MON-RAB-AV', 5),
        ('MAG-01', 'MON-SIL-TI', 3),
        ('MAG-01', 'MON-ENF-FL', 12),
        ('MAG-01', 'MON-HOM-MT', 9),
        ('MAG-01', 'VER-BIF-STD', 30),
        ('MAG-01', 'VER-174-ULT', 15),
        ('MAG-01', 'VER-AR-CRIZ', 20),
        ('MAG-01', 'VER-SOL-POL', 12),
        ('MAG-01', 'VER-HC-PROG', 8),
        ('MAG-01', 'LEN-TOR-CV', 25),
        ('MAG-01', 'LEN-MUL-CV', 18),
        ('MAG-01', 'LEN-SP-ACU', 22),
        ('MAG-01', 'ACC-COR-SIL', 45),
        ('MAG-01', 'ACC-KIT-RPA', 15),
        ('MAG-01', 'SOL-SAL-500', 30),
        ('MAG-01', 'SOL-PERO-CC', 10),
        ('MAG-01', 'ACC-SPR-100', 35),
        ('MAG-01', 'ACC-LPE-250', 20),
        ('MAG-01', 'ACC-VIS-COR', 25),
        ('DEP-01', 'MON-RB3025', 6),
        ('DEP-01', 'MON-OX8046', 4),
        ('DEP-01', 'MON-GEN-F', 8),
        ('DEP-01', 'MON-ENF-S', 5),
        ('DEP-01', 'VER-UNI-SP', 17),
        ('DEP-01', 'VER-PRO-PM', 10),
        ('DEP-01', 'VER-AR-BC', 14),
        ('DEP-01', 'VER-PHC-TR', 5),
        ('DEP-01', 'LEN-MEN-CV', 12),
        ('DEP-01', 'LEN-JOU-CV', 22),
        ('DEP-01', 'ACC-ETU-RIG', 14),
        ('DEP-01', 'ACC-MCF-X', 18),
        ('DEP-01', 'SOL-MUL-360', 10),
        ('DEP-01', 'SOL-SAL-250', 14),
        ('DEP-01', 'MON-TF5823', 3),
        ('DEP-01', 'MON-RAB-AV', 2),
        ('DEP-01', 'MON-SIL-TI', 1),
        ('DEP-01', 'MON-ENF-FL', 5),
        ('DEP-01', 'MON-HOM-MT', 4),
        ('DEP-01', 'VER-BIF-STD', 10),
        ('DEP-01', 'VER-174-ULT', 5),
        ('DEP-01', 'VER-AR-CRIZ', 7),
        ('DEP-01', 'VER-SOL-POL', 4),
        ('DEP-01', 'VER-HC-PROG', 3),
        ('DEP-01', 'LEN-TOR-CV', 10),
        ('DEP-01', 'LEN-MUL-CV', 6),
        ('DEP-01', 'LEN-SP-ACU', 8),
        ('DEP-01', 'ACC-COR-SIL', 20),
        ('DEP-01', 'ACC-KIT-RPA', 6),
        ('DEP-01', 'SOL-SAL-500', 12),
        ('DEP-01', 'SOL-PERO-CC', 4),
        ('DEP-01', 'ACC-SPR-100', 15),
        ('DEP-01', 'ACC-LPE-250', 8),
        ('DEP-01', 'ACC-VIS-COR', 10)
    ) AS d(warehouse_code, sku, qty)
    JOIN warehouse w ON w.code = d.warehouse_code
    JOIN product p ON p.sku = d.sku;

    INSERT INTO stock_level (warehouse_id, product_id)
    SELECT warehouse_id, product_id FROM tmp_opening
    ON CONFLICT DO NOTHING;

    INSERT INTO stock_movement (id, warehouse_id, product_id, type, quantity, balance_after, unit_cost, reason,
                                source_type, source_reference, created_by)
    SELECT t.movement_id, t.warehouse_id, t.product_id, 'OPENING', t.qty, l.quantity + t.qty, p.purchase_price,
           'Stock d''ouverture (données de démonstration)', 'DATA_IMPORT', 'DEMO-MOCK',
           (SELECT id FROM app_user WHERE username = 'import.demo')
    FROM tmp_opening t
    JOIN stock_level l ON l.warehouse_id = t.warehouse_id AND l.product_id = t.product_id
    JOIN product p ON p.id = t.product_id;

    UPDATE stock_level l
    SET quantity = m.balance_after, last_movement_id = m.id, updated_at = m.created_at
    FROM tmp_opening t
    JOIN stock_movement m ON m.id = t.movement_id
    WHERE l.warehouse_id = t.warehouse_id AND l.product_id = t.product_id;
END
$$;
