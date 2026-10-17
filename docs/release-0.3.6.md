# Camnory 0.3.6 - Interface TV partagee

## Changements

- Interface tablette horizontale adaptee a la telecommande, avec les reglages
  communs. Suppression des anciens ecrans TV dupliques.
- Accueil TV en pages de quatre cameras (2 x 2), sans vignette coupee en bas.
  Navigation directionnelle et indicateurs de page a droite.
- Selection par un halo fin adapte au theme. Marges video noires et nom compact.
- Son sur la seule camera selectionnee ; ouverture de toute la vignette en plein
  ecran, avec acces au son et aux commandes disponibles.
- Lecture Frigate plein ecran sur le flux principal original via MSE/Media3,
  au lieu d'agrandir un apercu JPEG. Apercus TV a 960 pixels.
- Menu Cameras retire sur TV seulement. Evenements en grille de six, avec
  filtres, pagination et lecture paysage accompagnee des informations a droite.
- Visibilite du bandeau recent et de la barre de cameras dans Apparence,
  masques par defaut sur TV. Connexions TV chiffrees conservees.

## Verification

Compilation, tests unitaires et lint Android. Tests physiques sur Smart_TV_Pro :
navigation et pages avec photos de test, halo, evenements et lecture paysage,
reglages partages, preservation des connexions, transport MSE et logo.
Test live limite a Devant : son de selection decode puis libere, plein ecran
3840 x 2160 HEVC, audio AAC decode et progression de lecture.
Aucune commande de lumiere, sirene, PTZ ou micro n'est envoyee par ces tests.
La grille utilise des photos de test et ne prouve pas le live des autres cameras.

Publication GitHub prerelease sur kotlin-native, pas sur le Play Store.

Resultat du 17 octobre 2026 : compilation et lint reussis ; 10 tests physiques
cibles reussis. Captures accueil, halo, grille et lecture des evenements inspectees.
APK version 0.3.6 / code 129, signe avec le certificat de mise a jour existant.
SHA-256 : df221d2a402c179a652fde6ed059feac2b14eefadff3b0db2881585ed1a1b9bd.
