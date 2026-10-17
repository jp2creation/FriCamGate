# Camnory 0.3.15

- Reception sur telephone : suppression du verrouillage global en paysage.
  Seule la TV impose cette orientation.
- Presentation verticale sur petit ecran : QR centre et dimensionne selon la
  largeur disponible, informations et boutons en dessous sans decalage lateral.
- Barres systeme, encoches et clavier pris en compte sur l ecran de reception.
- Texte des boutons adapte au theme clair comme au theme sombre.

Compilation et lint reussis, 205 tests unitaires sans echec (un ignore).
Le test natif du manifeste reproduit le
verrouillage dans la version precedente et passe avec la correction, sans
ouvrir d activite ni de camera. Le rendu sur telephone physique reste a confirmer.
Protocole de transfert et connexions inchanges. Preversion debug, code 138.
SHA-256 : `70303c1af4fae7902c53f52deb51557321e539c243e5544ec354137cabe5cbb7`.
