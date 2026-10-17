# Camnory 0.3.7 - Chargement et lecture TV

- Nouvel etat de connexion commun : message « Connexion... », petit indicateur
  dans la couleur du theme et dernier apercu adouci lorsqu'il est disponible.
  Sans apercu, fond sombre et petite icone camera. Aucun nom de fournisseur
  dans le message ; Home Assistant n'affiche plus « Connexion a Frigate ».
- Prechargement des pages suivantes sur l'accueil TV. Les apercus Frigate restent
  actifs ; Home Assistant prepare les sessions live hors ecran, en silence.
  Pour HLS, le decodeur est reserve aux videos visibles. Les connexions ONVIF
  sont preparees hors ecran et la lecture reprend en affichant la vignette.
- Lecture des evenements TV en MP4 original pour contourner l'echec HEVC/HLS
  observe sur la TV MStar. Validation d'une sequence Devant en 3840x2160 avec
  pause/reprise. Les adresses locales configurees sont reconnues sans autoriser
  de nouvelles origines ni transmettre de secrets a des tiers.
- Halo plus diffus et discret ; vignettes uniformes et recadrees sans deformation.
- Boutons tchat et cloche masques par defaut sur TV, reactivables independamment
  dans Theme et apparence. Les notifications systeme restent desactivees par
  defaut. Les petits boutons plein ecran des vignettes liees sont retires sur TV.
- Au demarrage, la barre termine a 100 % avant d'ouvrir le contenu, aussi en
  mode mouvements reduits. Un echec ne recoit pas d'animation de succes.

## Verification

Tests unitaires, compilation, lint et tests physiques cibles sur Smart_TV_Pro.
Les maquettes de connexion et de navigation utilisent des photos de test.
Le prechargement HA est exerce avec un serveur WebSocket local simule, sans
appeler les vraies cameras Ring. Le live et les enregistrements reels sont
testes uniquement sur Devant. Le delai reel de reveil Ring depend encore du
fournisseur et reste a confirmer par l'utilisateur.

Connexions chiffrees conservees. Aucun emulateur ni commande de lumiere,
sirene, PTZ ou micro. Publication GitHub prerelease, pas Play Store.

Resultat du 17 octobre 2026 : 182 tests unitaires sans erreur (un ignore),
compilation et lint reussis, 17 tests physiques cibles reussis. Captures natives
de connexion avec et sans apercu inspectees. La sequence reelle Devant et le
direct avancent en 3840x2160 ; la reprise via Play est validee.
Le test HA simule verifie une demande de flux et de playlist hors ecran,
l'absence de decodeur cache, le son coupe et la liberation a la sortie.

Version 0.3.7 / code 130. Certificat de mise a jour conserve.
SHA-256 APK : 57413d48347e776040f4d599b4339f1d408a6ae413f626959aa057b92610fc2f.
