# Camnory 0.3.10 - Grilles TV et navigation

- Reglages > Theme et apparence > Disposition des cameras : 1x1, 2x1,
  2x2, 3x3 et 4x4. Preference propre a la TV, 2x2 par defaut.
  Le selecteur de disposition de l'accueil propose aussi ces cinq choix.
- Pages entieres, points de navigation et deplacement a la telecommande.
  Haut depuis la premiere rangee de la premiere page rejoint le menu.
- Plus de bouton Charger plus dans Evenements et Notifications sur TV.
  La fin de l'historique charge automatiquement la page suivante ; une erreur
  conserve Reessayer. Les pages sans nouveaux elements terminent la pagination.
- Les demandes d'apercus detailles attendent 180 ms de selection stable,
  pour eviter une requete par vignette traversee rapidement. Les resultats
  obsoletes ne remplacent pas la selection courante.
- Message d'entree : Recherche de vos cameras. Presentation EN1/A2 conservee.

## Verification

- Compilation application et APK de test, lint : reussis.
- 187 cas unitaires, aucun echec ni erreur, un ignore.
- 19 tests physiques distincts cibles sur Smart_TV_Pro : cinq grilles,
  pages entieres, retour au menu, chargement automatique dans les deux vues,
  arret sur page repetee, presentation, lecture et audio.
- Captures natives 2x1, 3x3 et 4x4 inspectees apres mise en page complete.
  Tests des grandes grilles avec photographies fictives ; le decodage simultane
  de seize vrais flux n'a pas ete mesure. Aucune promesse de gain de latence
  serveur ; seule la reduction des requetes pendant le defilement est ajoutee.
- Lecture reelle limitee a Devant : enregistrement et direct 3840x2160,
  pause/reprise, lecture EN1, audio AAC decode. Aucun accessoire actionne.
- Configuration chiffree inchangee avant/apres les essais ; aucune suppression
  des donnees. Telephone et tablette conservent leurs choix de disposition.

## Livraison

Version 0.3.10 / code 133. GitHub prerelease, pas Play Store.
La version 0.3.9 reste disponible pour recuperation.

SHA-256 APK : 5a7d1effb0ba05ba042296015c82d87accdb9efe9f78f3aa159bdd741ff79768.
Certificat SHA-256 : 3227f243dea76dd0cc83a1868dbde07d0319eb2afe6d477b5cadc71f66cf0735.

Controle de livraison : telechargement public, comparaison de l'empreinte
et de la signature, installation sans effacement sur la TV, controle version
et conservation de la configuration.
