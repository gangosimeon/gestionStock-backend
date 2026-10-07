-- =============================================================================
-- Données de démonstration (profil dev uniquement) : marques et produits.
-- Reprise des 35 produits du backend simulé du front (mock-db.ts), sans les
-- quantités : le stock relève du module stock (mouvements), jamais du produit.
-- Migration répétable et idempotente (ON CONFLICT DO NOTHING).
-- =============================================================================

INSERT INTO brand (name) VALUES
    ('Ray-Ban'),
    ('Oakley'),
    ('Transitions'),
    ('CooperVision'),
    ('Alcon'),
    ('Tom Ford'),
    ('Silhouette'),
    ('Essilor'),
    ('Acuvue')
ON CONFLICT DO NOTHING;

INSERT INTO product (sku, name, category_id, brand_id, unit, purchase_price, retail_price, wholesale_price,
                     alert_threshold)
SELECT v.sku, v.name, c.id, b.id, v.unit, v.purchase_price, v.retail_price, v.wholesale_price, v.alert_threshold
FROM (VALUES
    ('MON-RB3025', 'Monture Ray-Ban RB3025', 'Montures', 'Ray-Ban', 'UNIT', 15000, 35000, 28000, 3),
    ('MON-OX8046', 'Monture Oakley OX8046', 'Montures', 'Oakley', 'UNIT', 18000, 42000, 34000, 3),
    ('MON-GEN-F', 'Monture Générique Femme', 'Montures', NULL, 'UNIT', 5000, 15000, 12000, 5),
    ('MON-ENF-S', 'Monture Enfant Sport', 'Montures', NULL, 'UNIT', 4500, 12000, 9500, 4),
    ('VER-UNI-SP', 'Verre Unifocal Sphérique', 'Verres correcteurs', NULL, 'PAIR', 3500, 8000, 6500, 10),
    ('VER-PRO-PM', 'Verre Progressif Premium', 'Verres correcteurs', NULL, 'PAIR', 12000, 28000, 23000, 5),
    ('VER-AR-BC', 'Verre Antireflet Bluecut', 'Verres correcteurs', NULL, 'PAIR', 8000, 18000, 15000, 5),
    ('VER-PHC-TR', 'Verre Photochromique Transitions', 'Verres correcteurs', 'Transitions', 'PAIR', 14000, 32000, 26000, 4),
    ('LEN-MEN-CV', 'Lentilles Mensuelles CooperVision', 'Lentilles de contact', 'CooperVision', 'BOX', 4500, 10000, 8500, 10),
    ('LEN-JOU-CV', 'Lentilles Journalières (boîte 30)', 'Lentilles de contact', NULL, 'BOX', 3000, 7000, 6000, 15),
    ('LEN-COL-AL', 'Lentilles Colorées Alcon', 'Lentilles de contact', 'Alcon', 'BOX', 5000, 12000, 10000, 8),
    ('ACC-ETU-RIG', 'Étui lunettes rigide', 'Accessoires', NULL, 'UNIT', 500, 1500, 1200, 20),
    ('ACC-MCF-X', 'Chiffon microfibre', 'Accessoires', NULL, 'UNIT', 200, 700, 550, 30),
    ('SOL-MUL-360', 'Solution multifonction 360ml Alcon', 'Solutions d''entretien', 'Alcon', 'BOTTLE', 2500, 5500, 4500, 15),
    ('SOL-SAL-250', 'Solution saline 250ml', 'Solutions d''entretien', NULL, 'BOTTLE', 1200, 3000, 2500, 20),
    ('MON-TF5823', 'Monture Tom Ford TF5823', 'Montures', 'Tom Ford', 'UNIT', 22000, 55000, 44000, 2),
    ('MON-RAB-AV', 'Monture Ray-Ban Aviator Classic', 'Montures', 'Ray-Ban', 'UNIT', 18000, 45000, 36000, 2),
    ('MON-SIL-TI', 'Monture Silhouette Titan Rimless', 'Montures', 'Silhouette', 'UNIT', 35000, 85000, 68000, 1),
    ('MON-ENF-FL', 'Monture Enfant Flex TR90', 'Montures', NULL, 'UNIT', 4000, 10000, 8000, 5),
    ('MON-HOM-MT', 'Monture Homme Métal Premium', 'Montures', NULL, 'UNIT', 8000, 22000, 18000, 3),
    ('VER-BIF-STD', 'Verre Bifocal Standard', 'Verres correcteurs', NULL, 'PAIR', 6000, 14000, 11500, 8),
    ('VER-174-ULT', 'Verre Ultra-Mince 1.74', 'Verres correcteurs', NULL, 'PAIR', 18000, 42000, 34000, 4),
    ('VER-AR-CRIZ', 'Verre AR Crizal Essilor', 'Verres correcteurs', 'Essilor', 'PAIR', 15000, 36000, 29000, 5),
    ('VER-SOL-POL', 'Verre Solaire Polarisé', 'Verres correcteurs', NULL, 'PAIR', 10000, 24000, 19500, 4),
    ('VER-HC-PROG', 'Verre Haute Correction Progressive', 'Verres correcteurs', NULL, 'PAIR', 20000, 48000, 38000, 3),
    ('LEN-TOR-CV', 'Lentilles Toriques Astig CooperV', 'Lentilles de contact', NULL, 'BOX', 5500, 12500, 10500, 8),
    ('LEN-MUL-CV', 'Lentilles Multifocal Presbytie', 'Lentilles de contact', NULL, 'BOX', 6500, 15000, 12000, 6),
    ('LEN-SP-ACU', 'Lentilles Sport Acuvue Oasys', 'Lentilles de contact', 'Acuvue', 'BOX', 7000, 16000, 13000, 8),
    ('ACC-COR-SIL', 'Cordon anti-perte Silicone', 'Accessoires', NULL, 'UNIT', 300, 1000, 800, 25),
    ('ACC-KIT-RPA', 'Kit réparation lunettes', 'Accessoires', NULL, 'UNIT', 700, 2500, 2000, 10),
    ('SOL-SAL-500', 'Solution saline stérile 500ml', 'Solutions d''entretien', NULL, 'BOTTLE', 2000, 4500, 3800, 15),
    ('SOL-PERO-CC', 'Clear Care solution peroxyde 360ml', 'Solutions d''entretien', NULL, 'BOTTLE', 3500, 8000, 6500, 8),
    ('ACC-SPR-100', 'Spray nettoyant lunettes 100ml', 'Accessoires', NULL, 'UNIT', 800, 2200, 1800, 20),
    ('ACC-LPE-250', 'Loupe lecture +2.5 dioptre', 'Accessoires', NULL, 'UNIT', 1500, 4500, 3500, 8),
    ('ACC-VIS-COR', 'Visserie remplacement monture', 'Accessoires', NULL, 'UNIT', 200, 800, 600, 20)
) AS v (sku, name, category_name, brand_name, unit, purchase_price, retail_price, wholesale_price, alert_threshold)
JOIN category c ON lower(c.name) = lower(v.category_name)
LEFT JOIN brand b ON lower(b.name) = lower(v.brand_name)
ON CONFLICT DO NOTHING;
