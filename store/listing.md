# Store listing texts

Ready to paste. SideQuest's description supports only: **bold**, *italic*, lists, links,
images, video (no tables, no HTML).

## SideQuest

**Title:** Quest Shelf – app library

**Summary (one sentence):** A replacement for Meta's app library that sorts, searches and groups your games, with the ones you own but have not installed next to the ones you have.

**Version code:** 4 (raise it with every release: that is what sends update notices to users)

**Tags:** Utility, Launcher, Library, Tool

**Devices:** Quest 3 (the only one it has been tested on)

**License:** MIT

**Comfort / play style:** not applicable (a 2D panel app), seated or standing

**Description:**

```
**Quest Shelf opens where Meta's app library opens, and shows your games the way you want them.**

Press the Meta button as usual: instead of the stock library you get a library that is sorted by what you played last, searchable, with folders and genres, and with your whole collection in one place.

**What it does**

- Installed games and games you own but have not installed, side by side, with a one-click switch between them
- Sorted by last played; drag a tile where you want it and it stays there
- Favorites, folders, genres, search, hidden apps
- Marks it works out by itself: new in your library, never launched, demo, barely played. Your own marks: completed, did not like
- A "Forgotten" view: games you have not played for 30 days (you choose), biggest first, so it is clear what to uninstall to free space
- A bar for the game that is running underneath, with "back to the game" and "quit the game"
- Buttons for Meta's own screens, and one that switches the home between mixed reality and Meta's environment (the same as a double tap on the headset)
- The cover of your most played game as a soft background
- Save and load your settings as a file
- English, Russian, German, Spanish, French

**Setup (a minute, no computer needed)**

Install, open Quest Shelf from "Unknown sources" and follow the short guide: it takes you to two system screens (Accessibility and Usage access) where you switch Quest Shelf on. To go back to Meta's library, switch it off there, or use the pause button inside Quest Shelf.

**Privacy**

No account, no analytics. Covers and names are loaded from OculusDB (package name or title only); one switch turns all network use off. Everything else stays on your headset. Details in the source.

**Good to know**

Quest Shelf relies on how Horizon OS behaves today, partly on things Meta does not document, so a system update can break something until Quest Shelf is updated. Tested on Quest 3 with Horizon OS v209. It is an unofficial tool and not affiliated with Meta.

Free and open source (MIT).
```

**Changelog for 1.0.1:** The app is now called Quest Shelf. Nothing else changed from 1.0.0.

**Links to add:** source code and issues on GitHub; (optional) donation link.

## Artwork (SideQuest App Studio)

| What | Size | Format | File |
|------|------|--------|------|
| Listing artwork | at least 1024×576, 16:9 | transparent PNG or SVG | `store/artwork.svg` |
| Background image | at least 1920×1080, 16:9, no text or logos | jpg or png | `store/background.png` |
| Screenshots | 3–6, at least 1024×576, 16:9 | jpg or png | made on the headset |
| Video (optional) | 16:9 | mov or avi | made on the headset |

### Screenshots (done)

Cut from the demo recording, 1440x810 (16:9), in `docs/img/`:

1. `screenshot-1-library.jpg`: the main screen with the Continue card.
2. `screenshot-2-not-installed.jpg`: games you own but have not installed.
3. `screenshot-3-forgotten.jpg`: the Forgotten view with sizes.
4. `screenshot-4-running.jpg`: the bar for the running game.
5. `screenshot-5-genres.jpg`: the genre list.
6. `screenshot-6-details.jpg`: a game's menu.

For SideQuest use 1, 2, 3, 4 and 6 (3–6 screenshots are recommended). Upload the same files to itch.io.

### Video (30–60 seconds)

Press the Meta button, Quest Shelf opens in place of Meta's library, scroll, switch to "Not installed",
open "Forgotten", open a game, press the Meta button again, tap "quit the game". Voice or
captions: "Press the Meta button, get a better library."

## GitHub release (v1.0.1)

**Tag:** v1.0.1   **Title:** Quest Shelf 1.0.1   **File:** `QuestShelf-1.0.1.apk` (signed) and its SHA-256

```
First public release.

A replacement for Meta's app library on Quest: installed and owned games together, sorting,
favorites, folders, genres, search, a view of forgotten games with sizes, a bar for the running
game, a mixed reality / environment button, settings backup. Free, MIT.

Install: enable developer mode, install the APK (SideQuest or `adb install`), open Quest Shelf from
"Unknown sources" and follow the short guide. See the README.
Tested on Quest 3, Horizon OS v209.
```

## itch.io

**Title:** Quest Shelf – app library for Quest   **Kind:** Tools   **Pricing:** free (or "pay what you
want" with 0 minimum, for donations)   **Tags:** quest, meta-quest, launcher, utility, app library

**Short description:** A better app library for Meta Quest: installed and owned games together,
sorting, folders, forgotten-games view.

Body: the SideQuest description above. Upload the same signed APK.

## Reddit post (r/OculusQuest, r/virtualreality: read each one's rules on self-promotion first)

**Title:** I made a free replacement for Meta's app library (sorts by last played, shows games you own but have not installed)

```
Meta's library has no folders, no sorting by last played, and hides your sideloaded apps. I built
Quest Shelf: it opens when you press the Meta button, instead of the stock library.

- installed + owned-but-not-installed games in one list
- favorites, folders, genres, search
- a "forgotten games" view with sizes, to free up space
- a bar to go back to or quit the running game
- a button for mixed reality / environment

Free, open source (MIT), no account, no analytics. Quest 3, sideload via SideQuest or adb.
SideQuest: <link>   Source: <link>
```
