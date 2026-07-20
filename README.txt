STREAMBOX VISION 8.0 — DÉMARRAGE SÉCURISÉ ET CONNEXION AUTOMATIQUE

CORRECTIONS
- La page de garde possède désormais une sortie de secours et ne peut plus rester affichée indéfiniment.
- L’application ouvre immédiatement les menus lorsqu’un profil valide est déjà enregistré.
- Le gros catalogue se synchronise ensuite en arrière-plan, sans bloquer l’écran d’accueil.
- Les identifiants sont enregistrés localement après la première connexion réussie.
- Les profils des versions 4, 6 et 7 sont migrés automatiquement.
- Le service worker utilise un nouveau cache et cherche toujours la version récente du HTML et du JavaScript.
- Si l’auto-connexion échoue, l’application revient proprement à la page de connexion.

INSTALLATION CONSEILLÉE
1. Générer un nouvel APK avec ce dossier/ZIP.
2. Utiliser index.html comme fichier de départ.
3. Conserver JavaScript, DOM Storage, Internet, accélération matérielle et cleartext HTTP activés.
4. Installer la version 8 par-dessus la précédente ou désinstaller l’ancienne si HTMLtoAPK conserve agressivement son cache.

SÉCURITÉ
Les identifiants sont conservés dans le stockage local de la WebView du téléphone. L’application ne fournit aucun abonnement ni contenu.
