# Camnory 0.3.9 - EN1 et accueil A2 sur TV

## Presentation validee

- Menu horizontal EN1 commun : logo, Accueil, Evenements, Notifications,
  Reglages. Plus de rail lateral sur TV, ni d'entree Cameras redondante.
  Les preferences des boutons tchat/cloche restent conservees.
- Accueil A2 : quatre videos de taille identique, pages entieres, selection
  fine dans la couleur du theme. Noms et fonds des noms non colores.
- Evenements EN1 : grand apercu natif, informations sur la droite et bande
  de cinq sequences en bas. Pagination gauche/droite, filtres dans le menu,
  retour sur la sequence et la page precedentes.
- Notifications EN1 : liste a gauche et apercu contextuel a droite ; filtre
  non lues et marquage des notifications chargees. Lire, y compris depuis
  l'apercu ou le plein ecran, marque la notification comme lue.
- Le focus actualise l'image et les informations sans lancer de flux video.
  OK lance l'enregistrement selectionne. Pause, recherche, son et plein ecran
  utilisent le lecteur existant ; les commandes se masquent hors interaction.
  Une reponse d'image devenue obsolete n'ecrase pas la selection courante.
- Une nouvelle page d'historique ne detruit pas une lecture en cours. Retour
  arrete la lecture et restaure la liste actualisee a la bonne position.

## Limites et compatibilite

Changements de presentation propres a la TV. Ni stockage de connexion ni
identifiants, adresses media, commande d'accessoire ou logique live modifies.
Telephone/tablette conservent leurs dispositions existantes. Conservation du
lecteur TextureView/MP4 original, du prechargement et du son live.

## Preuves locales du 17 octobre 2026

- Compilation APK/application de test et lint reussis.
- 185 cas unitaires, aucun echec ni erreur, un ignore.
- 17 tests physiques cibles reussis sur Smart_TV_Pro 1920x1080 : menu
  horizontal, quatre cameras entieres, bordure sans coloration du nom,
  pagination des sequences, liste/apercus, retour du plein ecran, etat lu,
  preferences de header, connexion, prechargement HA simule et chargement.
- Captures natives de l'accueil et des vues EN1 inspectees. Les essais de
  navigation utilisent des photographies de test, pas les vraies cameras.
- Lecture reelle sur Devant uniquement : progression 3840x2160, pause/reprise,
  lecture integree dans EN1 et absence d'interruption lors d'une actualisation
  d'historique. Direct 3840x2160 et audio AAC decode, volume TV non coupe.
- Empreinte du fichier chiffre de configuration identique avant/apres.
  Aucun emulateur, aucun test de sirene, lumiere, PTZ ou micro. Aucun nouveau
  test de delai de reveil des cameras Ring : ne pas le presenter comme mesure.

## Livraison

Version 0.3.9 / code 132. GitHub prerelease, pas Play Store. Ancienne 0.3.8
conservee pour recuperation ; aucune migration de donnees necessaire.
SHA-256 APK : 2488b137198936ef9ba30ca703b753469c59074c5ab72654eeef4225a7bfca9e.
Certificat SHA-256 : 3227f243dea76dd0cc83a1868dbde07d0319eb2afe6d477b5cadc71f66cf0735.
Verification de livraison : telecharger le fichier public, comparer l'empreinte
et le certificat, puis installer cet artefact sans effacer les donnees.
