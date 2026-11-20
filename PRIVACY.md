# Privacy

Quest Shelf has no account, no analytics, no advertising and no server of its own. Everything it
knows about you stays on your headset.

## What Quest Shelf keeps on the headset

Your favorites, folders, order of tiles, marks (completed, did not like), hidden apps, the time
each game was last started, and its own options. They are stored in the app's private storage.
"Save settings to a file" writes them to a file you choose; nothing is sent anywhere.

## What Quest Shelf sends over the network

Only to get game names, genres and cover pictures, and only if "Load covers, names and genres
from the internet" is on (it is by default; switching it off stops all network use):

- To [OculusDB](https://oculusdb.rui2015.me): the **package name**, **title** or **store id** of a
  game, so that it can answer with the name, genres and cover. It also downloads the list of
  store apps (a public catalog, the same for everyone).
- To [LauncherIcons](https://github.com/basti564/LauncherIcons) on GitHub: the **package name** of a
  game, when OculusDB has no cover for it.

Nothing else is sent: no account, no name, no play time, no settings. Which games you own is worked
out on the headset, from the library's local database and the bundled catalog. Be aware that
names and covers are asked for game by game, so these services can tell which games appear in your
list, and, like any web server, they see your network address. They have their own policies.

Links to Meta's store pages, the browser and system screens are opened by Android itself; Quest Shelf
does not see what happens there.

## Why Quest Shelf asks for its permissions

- **Accessibility service**: the only way for an app to start by itself after the headset boots and
  to notice that Meta's library has opened, so that Quest Shelf can open instead of it. It watches which
  windows are open and their titles. It does not read what other apps show, does not type or tap for
  you, and does not record anything.
- **Usage access**: the system's play statistics ("last played", time in game, which game is
  running, how much room an app takes). Read on the headset, never sent.
- **See all installed apps**: to list your games.
- **Internet**: the requests above.
- **Start after boot**: to open after the headset starts.
- **Remove apps**: to open the system's own uninstall dialog; Quest Shelf never removes anything by itself.

You can switch all of these off in the headset's settings. Quest Shelf then stops working as a library
replacement, and Meta's own library is back. Uninstalling Quest Shelf removes everything it has stored.

## Questions

Open an issue in the project's repository.
