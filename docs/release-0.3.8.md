# Camnory 0.3.8 - Navigation et historique TV

## Changements

- Selection des cameras : un seul trait fin dans la couleur d'accent, avec
  diffusion tres legere. Aucun zoom et aucune coloration du nom, de son fond
  ou de l'image. Cette derniere contrainte remplace la proposition initiale.
- Evenements : six grandes vignettes par page, pages regroupees par jour,
  metadonnees courtes, filtres existants accessibles en haut. Pagination DPAD
  et retour a l'evenement selectionne, sans repartir a la premiere page.
- Lecture : video sur environ 77 % de la largeur utile, panneau lateral
  compact avec date/heure precise et cameras synchronisees sur une colonne.
  Les commandes s'effacent pendant la lecture quand elles n'ont pas le focus,
  puis reviennent avec OK/Bas. Leur masquage ne redimensionne pas la video.
- Notifications : cinq lignes par page (quatre avec grande taille de texte),
  repere non lu, filtre non lues, lecture marquant l'element comme lu et action
  pour marquer les notifications chargees comme lues. Retour dans la boite de
  reception, et non dans les evenements. Etat lu stocke par serveur existant.
- Presentation propre a la TV. Aucun changement des flux, des identifiants,
  du prechargement, des accessoires ou des ecrans telephone/tablette.

## Verification

Tests unitaires : 184 cas, aucune erreur ni echec, un ignore. Compilation APK,
tests instrumentes et lint reussis sur macOS/Java 17. Pas d'emulateur.

Validation physique Smart_TV_Pro 1920x1080 : grille de quatre complete, bordure
fine et nom non colore, six evenements et cinq notifications par page,
navigation/retour sur la page selectionnee, filtre non lu et marquage, shell
partage, connexion, prechargement HA simule, bannere et chargement initial.
Les captures des listes utilisent des photographies de test, pas les cameras
reelles. Les preferences de lecture du serveur de test sont nettoyees.
Resultat : 15 tests de navigation/integration et deux tests media reels reussis,
soit 17 tests physiques cibles sur l'artefact final.

Lecture reelle limitee a Devant : enregistrement MP4 3840x2160, progression,
masquage/reapparition des commandes sans changement de hauteur, pause/reprise.
Direct 3840x2160, piste AAC selectionnee et decodee, volume TV non coupe.
La perception du contour depuis le canape reste soumise au retour utilisateur.
Ne pas presenter ces tests comme une nouvelle mesure du reveil des vraies Ring.

Connexions chiffrees conservees (empreinte avant/apres identique). Aucune
commande de lumiere, sirene, PTZ ou micro. Ancienne prerelease 0.3.7 conservee
pour recuperation ; aucun schema ni stockage de connexion migre.

## Artefact

Version 0.3.8 / code 131. Publication GitHub prerelease, pas Play Store.
SHA-256 APK : 4ec6156a4d8c6289bbab62859006a4b724b7f8f057dc35855e9f369de973ca91.
Certificat SHA-256 : 3227f243dea76dd0cc83a1868dbde07d0319eb2afe6d477b5cadc71f66cf0735.
Le fichier public doit etre telecharge, compare a cet artefact puis installe
sans effacement des donnees sur la TV.
