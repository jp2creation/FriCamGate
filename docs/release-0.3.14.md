# Camnory 0.3.14

- Camera IP / Webcam : marges des barres systeme, encoches et clavier prises
  en compte. Le titre peut revenir a la ligne et les barres suivent le theme.
- Chronologie : le bouton entoure sur la capture utilise les quatre carres,
  sans changer son action.
- Telephone et tablette : prechargement des cameras IP, ONVIF et Home Assistant
  de l accueil, y compris hors du viewport, comme sur TV. Frigate demarrait deja
  les flux de l accueil. L arriere-plan et le verrouillage arretent les connexions.
- Un seul filtre d accueil : Serveur et Categories dans la meme popup de 70 %
  maximum, avec Appliquer et Reinitialiser. Les deux criteres se combinent et
  fermer sans appliquer ne les modifie pas. La ligne de categorie est supprimee.
- Quand la barre Cameras est masquee et que des categories existent, le meme
  bouton reste accessible dans l en-tete sans ligne supplementaire.

## Verification

Compilation et lint reussis, 205 tests unitaires sans echec (un ignore).
Deux tests natifs isoles reussis sans afficher d activite
ni connecter de camera : marges systeme/clavier, selection serveur et categorie,
conservation des choix entre onglets et reinitialisation sur confirmation.
Les tests des anciens parcours sont adaptes a la popup commune.

Le rendu et le prechargement sur le telephone physique restent a confirmer.
Pas de modification de protocole de transfert, identifiants ou commandes de
camera. Pas de migration des connexions. Preversion debug, code 137.
SHA-256 : `3e4d383adc072822117784450fa0fe0ed5acff4491dbc87b9f26579b1cc9e97b`.
