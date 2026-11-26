# Skytac API

Kleiner Server zwischen der App und Claude. Er hält den Claude-Schlüssel, erkennt den angemeldeten Nutzer und zählt Credits. Fliegen, Wächter und Sticks bleiben auf dem Handy.

- `POST /v1/messages` mit `Authorization: Bearer <Supabase-Token>` und `x-skytac-kind: ask | explain | check | spot | edit | plan`. Der Rumpf ist derselbe wie für die Claude Messages API; Modell und Antwortlänge setzt der Server.
- `GET /v1/me` gibt Guthaben und Stufe.
- `GET /health`.

Preise in Credits und alle Grenzen stehen in `src/policy.mjs`. Abgebucht wird vor der Anfrage, bei einem Fehler von Claude wird zurückgebucht.

## Einrichten

1. Eigenes Supabase-Projekt (nicht das von GIBS). `sql/001_init.sql` einspielen. Anonyme Anmeldung oder E-Mail-Anmeldung einschalten.
2. Fly-App `skytac-api`, Region `fra`. Geheimnisse setzen, nie ins Repo:
   `fly secrets set ANTHROPIC_API_KEY=… SUPABASE_URL=… SUPABASE_ANON_KEY=… SUPABASE_SERVICE_KEY=… -a skytac-api`
3. `fly deploy` in diesem Ordner.

Stellschrauben (optional): `MODEL_IMAGE`, `MODEL_PLAN`, `START_CREDITS` (40), `DAILY_CREDIT_CAP` (20000 Credits am Tag über alle Nutzer), `RATE_PER_MIN` (20).

## Prüfen

`npm test` (ohne Netz, Supabase und Claude sind Attrappen).

Noch nicht gebaut: Käufe über Google Play gutschreiben, monatliche Credits für Abos, Anmeldung in der App.
