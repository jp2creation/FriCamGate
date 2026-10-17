# Camnory 0.3.11 - Camera IP / Webcam

## Nouveautes

- Nouvelle source independante d'ONVIF : Camera IP / Webcam, dans le choix
  initial de connexion et Reglages > Connexions.
- Ajout manuel, modification et suppression : nom, adresse du flux, activation
  facultative des identifiants et test du direct avant enregistrement.
- RTSP via le lecteur Media3 TCP existant ; video HTTP HLS/MP4, MJPEG et images
  JPEG actualisees. L'adresse IP seule ne suffit pas si la camera expose son
  flux sur un chemin particulier. Une page HTML constructeur n'est pas un flux.
- Authentification HTTP Basic ; RTSP Basic/Digest via Media3. Aucun contournement
  TLS, aucune transmission d'identifiants a une autre origine via redirection.
  HTTP non chiffre reste destine au reseau local de confiance ; preferer HTTPS
  lorsque la camera le propose. Les identifiants ne sont pas affiches en liste.
- Stockage chiffre, accueil telephone/tablette/TV, plein ecran, son si present,
  ordre/visibilite et filtre de source. Les controles impossibles ne sont pas
  inventes : pas de PTZ, sirene ou lumiere via un simple lien video.
- Transfert chiffre telephone vers TV incluant les cameras IP. Mettre les deux
  appareils a jour pour transferer cette nouvelle source. Les anciennes
  configurations restent lisibles, sans migration destructive.

## Implementation

IpCamera valide les adresses et isole le stockage ; IpCameraActivity gere le
formulaire protege par le verrou existant. IpCameraCard gere la lecture,
l'authentification et l'arret des requetes hors ecran/arriere-plan. Les images
recues ont des limites de taille, de resolution et de file d'attente.
Le schema TV v2 ajoute la liste IP ; les ecritures Frigate, ONVIF, HA et
d'organisation conservent cette liste. Sans camera IP, le schema v1 est conserve.

## Verification du 17 octobre 2026

- Compilation application/APK de test et lint reussis.
- 192 cas unitaires, aucun echec ni erreur, un ignore : adresses, secrets,
  compatibilite des configurations et limites du lecteur JPEG notamment.
- 23 tests physiques cibles reussis sur la TV : formulaire, ajout et edition
  dans un fichier chiffre isole, mot de passe conserve ou retire volontairement,
  JPEG/MJPEG et MP4 locaux, authentification HTTP valide et refusee, arret des
  requetes, connexion RTSP directe avec et sans identifiants.
- Capture native du formulaire inspectee : champs lisibles et teinte du theme.
- Regression EN1/A2, grilles TV, historique, stockage partage et lecture/audio
  Devant verifies. Aucun test d'accessoire, aucun emulateur.
- Configuration chiffree reelle identique avant/apres les tests. Les essais
  d'ajout n'ont utilise qu'un fichier QA isole, supprime ensuite.

Limites des preuves : aucune vraie webcam supplementaire n'a ete fournie.
Les tests RTSP verifient la negotiation/authentification, pas le decodage d'une
nouvelle camera RTSP. Le decodage video a ete verifie sur le MP4 de test et sur
Devant. Pas d'essai physique HTTPS, HLS direct ou HTTP Digest (non implemente).
Le support RTSP utilise le module Media3 1.5.1 deja present, sans mise a jour
de dependance : https://developer.android.com/media/media3/exoplayer/rtsp

## Livraison

Version 0.3.11 / code 134, prerelease GitHub et non Play Store. La 0.3.10
reste disponible. Les essais ne modifient aucune connexion utilisateur.
SHA-256 APK : b7bd276a0a4a7a54a0d607152f7ba0c0003b453c25f683360713ba6dd3f9a7bf.
Certificat SHA-256 : 3227f243dea76dd0cc83a1868dbde07d0319eb2afe6d477b5cadc71f66cf0735.
Controle de livraison : telecharger l'APK public, comparer empreinte et signature,
installer cet artefact sans effacement, verifier version et configuration.
