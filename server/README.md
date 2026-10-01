# Serveur de Drop

API et dashboard d'admin de Drop, sur agentia. Le téléphone y envoie chaque show par tranches de 30 s ; le serveur
garde tout, analyse chaque morceau entendu en entier pour mémoriser ses vrais drops, et recueille les verdicts de
Louis (« drop » ou « pas un drop ») qui servent de tests au réglage.

- Service : `systemctl --user status drop-api` (`~/.config/systemd/user/drop-api.service`), écoute sur 127.0.0.1:5720.
- Accès : `https://agentia.tail16ac81.ts.net:4445`, tailnet seulement (`tailscale serve --https=4445`, jamais Funnel).
  Chaque requête doit porter l'en-tête `Tailscale-User-Login` d'un compte autorisé, posé par `tailscale serve`.
- Données : `~/drop-data` (`drop.db` SQLite, `sessions/<id>/<rang>.csv.gz`, `apk/drop.apk` servi sur `/apk`).
- Analyse : `analysis.py` (même règle de drop que `Analyzer.kt`, mais sur le morceau entier).
- Anciens enregistrements du téléphone : `python3 import_recording.py <csv> --dur Titre=secondes`. Ils servent au
  réglage, jamais à la mémoire (positions approximatives).

Routes principales : `POST /api/sessions/<id>/chunks?seq=N` (CSV gzip), `POST /api/sessions/<id>/end`,
`GET /api/memory` (pour le téléphone), `GET /api/sessions`, `GET /api/sessions/<id>`, `GET /api/sessions/<id>/series`,
`GET /api/tracks`, `POST /api/tracks/<clé>/forget`, `POST /api/verdicts`, `GET /api/calibration`, `POST /api/reanalyse`.
