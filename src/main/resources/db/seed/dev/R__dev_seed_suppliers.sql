-- =============================================================================
-- Données de démonstration (profil dev uniquement) : fournisseurs.
-- Reprise des 5 fournisseurs du backend simulé du front (mock-db.ts) et du fournisseur
-- habituel de chaque produit (supplierId du mock, rapproché par SKU).
-- Migration répétable et idempotente : exécutée après R__dev_seed_catalog (ordre
-- alphabétique), elle ne renseigne que les produits encore sans fournisseur.
-- =============================================================================

INSERT INTO supplier (code, name, phone, email, address, delivery_lead_time_days, payment_terms_days)
VALUES
    ('FOU-ESSILOR', 'Essilor Distribution CI', '+225 20 25 00 10', 'commandes@essilor.ci', 'Zone Ind. Yopougon, Abidjan', 5, 30),
    ('FOU-LUXOTTICA', 'Luxottica Afrique de l''Ouest', '+225 20 31 44 00', 'orders@luxottica-aow.com', 'Plateau, Abidjan', 10, 45),
    ('FOU-COOPER', 'CooperVision Afrique', '+225 22 47 80 00', 'supply@coopervision.ci', 'Marcory, Abidjan', 7, 30),
    ('FOU-TRANSITIONS', 'Transitions Optical', '+225 20 56 12 30', 'orders@transitions.ci', 'Koumassi, Abidjan', 12, 30),
    ('FOU-ALCON', 'Alcon Laboratoires CI', '+225 20 33 90 00', 'alcon@alcon.ci', 'Cocody, Abidjan', 8, 0)
ON CONFLICT DO NOTHING;

UPDATE product p
SET default_supplier_id = s.id
FROM (VALUES
    ('FOU-ESSILOR', ARRAY['VER-UNI-SP', 'VER-PRO-PM', 'VER-AR-BC', 'ACC-MCF-X', 'VER-BIF-STD', 'VER-174-ULT',
                          'VER-AR-CRIZ', 'VER-SOL-POL', 'ACC-SPR-100', 'ACC-LPE-250']),
    ('FOU-LUXOTTICA', ARRAY['MON-RB3025', 'MON-OX8046', 'MON-GEN-F', 'MON-ENF-S', 'ACC-ETU-RIG', 'MON-TF5823',
                            'MON-RAB-AV', 'MON-SIL-TI', 'MON-ENF-FL', 'MON-HOM-MT', 'ACC-COR-SIL', 'ACC-KIT-RPA',
                            'ACC-VIS-COR']),
    ('FOU-COOPER', ARRAY['LEN-MEN-CV', 'LEN-JOU-CV', 'LEN-TOR-CV', 'LEN-MUL-CV']),
    ('FOU-TRANSITIONS', ARRAY['VER-PHC-TR', 'VER-HC-PROG']),
    ('FOU-ALCON', ARRAY['LEN-COL-AL', 'SOL-MUL-360', 'SOL-SAL-250', 'LEN-SP-ACU', 'SOL-SAL-500', 'SOL-PERO-CC'])
) AS m (supplier_code, skus)
JOIN supplier s ON s.code = m.supplier_code
WHERE p.sku = ANY (m.skus)
  AND p.default_supplier_id IS NULL;
