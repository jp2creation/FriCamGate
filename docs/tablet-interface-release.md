# Camnory 0.3.18 - Interface tablette

- Accueil paysage avec navigation laterale, grille et activites a droite.
- Accueil portrait avec navigation basse, activites recentes detaillees et grille
  a deux colonnes par defaut; les preferences d'affichage restent modifiables.
- Timeline et lecture des evenements en deux panneaux sur les grandes tablettes
  paysage; disposition empilee en portrait et dans les fenetres etroites.
- Notifications en deux colonnes en paysage large.
- Reglages paysage avec rubriques toujours accessibles a gauche.
- Bandeau protege des barres systeme; affichage video sans recadrage force dans
  la timeline tablette. Reorganisation des pages lors d'une rotation.
- Connexions et donnees conservees, sans migration de stockage.

## Verification

Version 0.3.18, code 141. Verification native sur emulateur Android 35, ecran
1600x1000 puis 1000x1600, densite 213 (environ 1202x751 dp et 751x1202 dp).
Le test couvre accueil, notifications, lecture d'evenement, timeline, reglages,
apparence et cameras; controle de navigation, insets et rotation des reglages.
Les images et l'enregistrement sont des fixtures de demonstration, injectees en
memoire uniquement. Aucune commande physique de camera n'est envoyee.

208 tests unitaires, aucune erreur, un test ignore. Build APK et lint Android
executes; les avertissements historiques ne sont pas assimiles a une absence
totale d'avertissements (274 avertissements, aucune erreur lint). Le test tablette
instrumente a passe dans chacune des deux orientations sur l'APK final :
35,25 secondes en portrait et 36,50 secondes en paysage. La duree du clip local
est verifiee positive; les connexions chiffrees sont identiques avant et apres
les tests (controle SHA-256). Les captures ne prouvent pas la lecture
des cameras de l'utilisateur; aucun test sur tablette physique n'est revendique.

APK SHA-256: `010c4503a53ce25888ee76e0303ed7f8d765099b23e2759eb10c2f0026436cd8`.
Signature debug conservee pour une installation en mise a jour.

Retour arriere: APK 0.3.17 apres sauvegarde si necessaire; cette version ne change
pas le format des connexions. La procedure Android de downgrade peut demander
des options specifiques, ne pas desinstaller ni effacer les donnees par defaut.
