-- delete default grid view
DELETE FROM annis343.resolver_vis_map WHERE vis_type='grid' AND corpus='streusle';

-- disable default kwic view
INSERT INTO annis343.resolver_vis_map 
(corpus, vis_type, display_name, visibility, "order")
VALUES ('streusle', 'kwic', 'kwic', 'removed', -1);

-- make a grid view for ss, ss2, lexcat
INSERT INTO annis343.resolver_vis_map
(corpus, namespace, element, vis_type, display_name, visibility, "order", mappings)
VALUES ('streusle', 'default_ns', 'node', 'grid', 'lexical', 'permanent', 1, 'annos:lexcat,ss,ss2,tok');

-- make a view for extra annos
INSERT INTO annis343.resolver_vis_map
(corpus, namespace, element, vis_type, display_name, visibility, "order", mappings)
VALUES ('streusle', 'default_ns', 'node', 'grid', 'extra', 'hidden', 999, 'annos:sent_id, lextag, config, tok');

-- rename edge views
UPDATE annis343.resolver_vis_map SET display_name='deps' WHERE display_name='ud (default_ns)';
UPDATE annis343.resolver_vis_map SET display_name='enhanced deps' WHERE display_name='ude (edeps)';
UPDATE annis343.resolver_vis_map SET display_name='cycle edges' WHERE display_name='udecycle (cycle)';
UPDATE annis343.resolver_vis_map SET display_name='govobj',"order"=5 WHERE display_name='govobj (govobj)';
