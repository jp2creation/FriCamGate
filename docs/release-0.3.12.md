# Camnory 0.3.12

## Noms et categories des cameras

- Reglages > Cameras > Categories : le bouton + cree une categorie, puis
  ouvre la liste des cameras a cocher. Une camera peut appartenir a plusieurs
  categories, quelle que soit sa source.
- Ouvrir une categorie permet de modifier son nom et ses cameras. Supprimer
  une categorie ne supprime jamais les cameras.
- Le crayon de chaque camera modifie son nom affiche sans renommer la source.
- L'accueil permet de filtrer par categorie ou d'afficher toutes les cameras.
- Navigation TV : liens de focus explicites entre champs, cases et actions.
- Metadonnees conservees localement sur chaque appareil ; pas de nouvelle
  synchronisation entre appareils dans cette version.

## Verification

Build, lint et tests unitaires : 197 cas, aucune erreur, un cas ignore.
18 tests natifs reussis sur la TV physique : categories, grilles/navigation,
reglages partages, connexion, cameras IP et evenements. Donnees fictives et
preferences isolees pour les essais de categories.
Le parcours categorie est teste avec les touches DPAD et la saisie clavier,
pas seulement avec des clics directs sur les composants.
Aucune commande de sirene, lumiere, PTZ ou micro n'est envoyee pendant la QA.
La configuration chiffree des connexions de la TV est restee identique.

APK de preversion debug, version 0.3.12 / code 135, meme signature Android.
SHA-256 : `bafd155325ecc8b279d437bc67a6637a58397850f119da5aedfc82f88d475a26`.
