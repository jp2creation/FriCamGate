# Camnory 0.3.13 - Transfert personnalisable

## Fonctionnement

- Reglages > Transfert entre appareils : scanner un QR, saisir une invitation
  complete ou recevoir une configuration sur cet appareil.
- Quatre choix : tout, apparence uniquement, cameras uniquement, personnalise.
- Cameras selectionnables par categorie ou individuellement, sans doublons.
- Choix de copier les categories et affectations, avec les noms personnalises.
- Apparence : theme, accent, fond, taille du texte, dispositions, boutons et
  bandeaux visibles. Les preferences de securite et alertes ne sont pas copiees.
- Recapitulatif et consentement aux identifiants avant envoi ; confirmation
  avant import. Ajout sans effacement par defaut, remplacement explicite des
  rubriques incluses uniquement.
- Une fusion impossible est refusee sans modification, notamment deux serveurs
  Home Assistant differents (l application ne gere qu une connexion HA).
- Interface dans les couleurs du theme, cases coherentes, choix en panneau,
  navigation a la telecommande et six langues.

## Securite et limites

Transfert TLS epingle, invitation temporaire et a usage unique, stockage chiffre.
Les identifiants gardent leurs droits serveur : la selection des cameras n est
pas un nouveau controle d acces cote serveur. Aucune commande de sirene,
lumiere, PTZ ou microphone. Aucune synchronisation permanente.

Les deux appareils doivent utiliser 0.3.13 ou ulterieur pour un transfert selectif.
Le numero a six chiffres verifie l association ; la saisie manuelle utilise le
code complet CTV1. Le QR reste le parcours recommande. Reception aussi possible
sur telephone/tablette ; pas de copie dans les anciens magasins de mots de passe.

## Verification

Build, lint et 205 tests unitaires : aucune erreur, un test ignore. Les tests
couvrent schemas, selections, metadonnees, fusion, conflits et transport TLS.
23 tests natifs reussis sur TV physique avec sources fictives et stockage isole :
selection par categorie, revue via DPAD, import ajout/remplacement, largeur
telephone et reception TLS avec confirmation sans ecriture de donnees reelles.
Le scan QR sur un second telephone physique n est pas confirme dans cette QA.
La preuve TLS sur TV ne remplace pas ce dernier essai entre deux appareils.

Preversion debug 0.3.13 / code 136, signature inchangee.
SHA-256 : `c8a64f65a774068c01bce7255c4cae2c299c5a7c66057344b6093f8b60b1c2bb`.
