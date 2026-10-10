-- =============================================================================
-- Données de démonstration (profil dev uniquement) : clients.
-- Reprise des 15 clients du backend simulé du front (mock-db.ts). Les institutions sont
-- des entreprises ; grossistes et opticiens achètent au tarif de gros. Aucun solde : les
-- créances naîtront des ventes à crédit (module ventes).
-- Migration répétable et idempotente (codes fixes, ON CONFLICT DO NOTHING).
-- =============================================================================

INSERT INTO customer (code, type, name, phone, email, price_category, credit_limit, payment_terms_days)
VALUES
    ('CLI-DEMO-01', 'INDIVIDUAL', 'Aminata Traoré', '+225 05 10 20 30', 'aminata.t@gmail.com', 'RETAIL', 0, 0),
    ('CLI-DEMO-02', 'COMPANY', 'Optique Pro Dakar', '+221 33 820 00 01', 'achats@optiquepro.sn', 'WHOLESALE', 200000, 30),
    ('CLI-DEMO-03', 'INDIVIDUAL', 'Kouadio Jean-Pierre', '+225 05 55 44 33', 'kj.pierre@yahoo.fr', 'RETAIL', 50000, 30),
    ('CLI-DEMO-04', 'COMPANY', 'Clinique Vision Plus', '+225 20 21 00 55', 'vision.plus@clinique.ci', 'RETAIL', 500000, 45),
    ('CLI-DEMO-05', 'INDIVIDUAL', 'Ibrahim Coulibaly', '+225 07 77 88 99', NULL, 'RETAIL', 0, 0),
    ('CLI-DEMO-06', 'INDIVIDUAL', 'Mariam Bamba', '+225 05 34 56 78', 'mariam.b@gmail.com', 'RETAIL', 30000, 30),
    ('CLI-DEMO-07', 'COMPANY', 'Grossiste Abidjan SA', '+225 20 33 44 55', 'achats@grossiste-abi.ci', 'WHOLESALE', 1000000, 30),
    ('CLI-DEMO-08', 'INDIVIDUAL', 'Yves Aka', '+225 07 11 22 33', 'yves.aka@email.com', 'RETAIL', 0, 0),
    ('CLI-DEMO-09', 'INDIVIDUAL', 'Nadia Ouédraogo', '+226 70 11 22 33', 'nadia.o@email.bf', 'RETAIL', 75000, 30),
    ('CLI-DEMO-10', 'COMPANY', 'Centre Médical Cocody', '+225 22 44 00 88', 'medecin@cmc.ci', 'RETAIL', 300000, 45),
    ('CLI-DEMO-11', 'COMPANY', 'Opticiens Réunis SA', '+225 20 22 33 44', 'achats@opticiens-reunis.ci', 'WHOLESALE', 750000, 30),
    ('CLI-DEMO-12', 'INDIVIDUAL', 'Soumaila Koné', '+225 07 55 66 77', 'soumaila.k@yahoo.fr', 'RETAIL', 0, 0),
    ('CLI-DEMO-13', 'COMPANY', 'Hôpital Général d''Abobo', '+225 20 38 10 20', 'commandes@hga.ci', 'RETAIL', 1500000, 60),
    ('CLI-DEMO-14', 'INDIVIDUAL', 'Adjoua Diomandé', '+225 05 77 88 12', NULL, 'RETAIL', 0, 0),
    ('CLI-DEMO-15', 'COMPANY', 'Association Voir Mieux CI', '+225 22 47 50 00', 'contact@voirmieux.ci', 'RETAIL', 400000, 30)
ON CONFLICT DO NOTHING;
