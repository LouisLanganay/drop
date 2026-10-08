# Drop

App Android personnelle : elle écoute la musique au micro du téléphone et pilote les lampes Hue en temps réel (tempo et battements, volume, montées, drops, stroboscope, palettes selon le style).

- Flux temps réel du pont Hue (mode divertissement) : DTLS 1.2 à clé partagée, port UDP 2100, messages HueStream v2, 50 envois par seconde.
- Analyse sur le téléphone : spectre sur 2048 points, attaques par flux spectral, tempo par autocorrélation, phase des temps recalée sur les attaques, détection des montées et des drops.
- Compilation par GitHub Actions ; la clé de signature stable est dans le secret `DEBUG_KEYSTORE_B64`.
- Adresse du serveur (`server/`) fixée à la compilation : `drop.server=https://...` dans `local.properties`, ou
  `-Pdrop.server=...`, ou la variable `DROP_SERVER_URL` (secret de dépôt du même nom pour la CI). Vide, l'app ne
  contacte aucun serveur.

Stroboscope limité à 10 flashs par seconde, déconseillé aux personnes photosensibles.
