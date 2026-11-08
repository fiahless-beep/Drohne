#!/bin/sh
# Ein Dutzend Testrouten rund um Rua Rodrigo da Fonseca 190 (Felix 8.11.: „über Nacht mindestens ein Dutzend Routen testen“).
# Erzeugt plaene/test/*.json und per tools/plan_artifact.py die 3D-Exporte plaene/3d/*.json (für planer/index.html und die App).
set -e
H="38.72783 -9.15727"
S=tools/plan_tief.py
python3 $S t01-amoreiras-jardim $H 38.72219 -9.15631 20 "Zuhause → Jardim das Amoreiras (400 m süd), so tief wie möglich, Foto am Ziel, zurück" > plaene/test/t01-amoreiras-jardim.json
python3 $S t02-torres-amoreiras $H 38.72379 -9.15966 20 "Zuhause → Rua das Amoreiras (Türme), so tief wie möglich – Test Gebäude-Wächter an hohen Türmen" > plaene/test/t02-torres-amoreiras.json
python3 $S t03-amalia-rodrigues $H 38.73144 -9.15519 20 "Zuhause → Jardim Amália Rodrigues (oben am Park), so tief wie möglich, bergauf" > plaene/test/t03-amalia-rodrigues.json
python3 $S t04-liberdade $H 38.72031 -9.1462 20 "Zuhause → Avenida da Liberdade, so tief wie möglich, bergab über Rua Castilho" > plaene/test/t04-liberdade.json
python3 $S t05-camilo-castelo-branco $H 38.72542 -9.14827 20 "Zuhause → Jardim Camilo Castelo Branco (hinter der Praça), so tief wie möglich" > plaene/test/t05-camilo-castelo-branco.json
python3 $S t06-rato $H 38.72292 -9.15165 20 "Zuhause → Rua Castilho/Braamcamp (Rato), so tief wie möglich, dichte Bebauung" > plaene/test/t06-rato.json
python3 $S t07-herculano $H 38.72337 -9.14817 20 "Zuhause → Rua Alexandre Herculano, so tief wie möglich" > plaene/test/t07-herculano.json
python3 $S t08-castilho-kurz $H 38.72681 -9.15517 12 "Zuhause → Rua Castilho (200 m), mindestens 12 m – kürzeste Strecke" > plaene/test/t08-castilho-kurz.json
python3 tools/plan_gebaeudefoto.py t09-fassade-castilho 38.727282 -9.15579 $H > plaene/test/t09-fassade-castilho.json
python3 tools/plan_gebaeudefoto.py t10-fassade-rdf190 38.72783 -9.15727 > plaene/test/t10-fassade-rdf190.json
cp plaene/rodrigo-da-fonseca-valenciana.json plaene/test/t11-valenciana.json
cp plaene/marques-de-pombal-park-tief.json plaene/test/t12-pombal-tief.json
for f in plaene/test/t*.json; do python3 tools/plan_artifact.py "$f" --out "/tmp/claude-0/planer-$(basename "$f" .json).html"; done
