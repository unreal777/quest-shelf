# Shelf

A replacement for the app library of Meta Quest headsets. It opens where Meta's library would
open, and shows your games the way you want them: sorted, grouped, searchable, with the ones
you own but have not installed next to the ones you have.

Shelf is an unofficial tool. It is not made or endorsed by Meta.

## What it does

- Opens on the menu button in place of Meta's library, and after the headset starts.
- Shows installed and owned-but-not-installed games together, with a one-click switch between them.
- Sorts by last played by default; tiles you moved by hand (drag and drop) stay where you put them.
- Favorites, folders, genres, search, hidden apps.
- Marks worked out by itself: new in the library, never launched, demo, barely played. Your own
  marks: completed, did not like.
- A bar for the game that is running underneath, with "back to the game" and "quit the game".
- A row of buttons for Meta's own screens: its library, settings, store, notifications, browser and so on.
- The cover of your most played (or chosen) game as the background.
- English, Russian, German, Spanish, French.

## Install

1. Turn on developer mode for the headset (Meta Horizon phone app → Devices → Developer mode).
2. Install the APK, with SideQuest or with `adb install Shelf.apk`.
3. Open Shelf from the library (filter "Unknown sources"). A short guide opens by itself and
   leads to two system screens:
   - **Accessibility → Shelf**: lets Shelf open instead of Meta's library and after the headset
     starts. If the switch is greyed out, open the app's page in the system settings, then the ⋮
     menu, then "Allow restricted settings".
   - **Usage access → Shelf**: "last played", play time, and the bar for the running game.

No computer is needed after the APK is on the headset. If a headset has no such system screens,
`install.ps1` does the same from a PC over USB (it needs `adb`).

To go back to Meta's library, switch the service off on the same Accessibility screen, or
uninstall Shelf. Nothing in the system is changed for good.

## What it sends over the network

Covers, store names and genres come from [OculusDB](https://oculusdb.rui2015.me), with
[LauncherIcons](https://github.com/basti564/LauncherIcons) as a spare source for covers. To get
them, Shelf sends the package name, the title or the store id of a game, nothing else: no account, no list of
what you own, no usage data. A catalog of store apps is bundled with the app, so the list of
owned games is worked out on the headset. The switch "Load covers, names and genres from the
internet" turns all network use off.

Shelf has no analytics and no account of its own.

## Limits worth knowing

- It relies on how Horizon OS behaves today, partly on things Meta does not document. A system
  update can break a feature until Shelf is updated. It was developed on a Quest 3 with Horizon OS v209.
- Games played before the headset started keeping statistics (or before a factory reset) show no
  play time.
- A few owned games are missing from the public catalog and do not show up until installed.
- "Profile" and "Quick settings" in the bottom row open Meta's whole menu: they are pages of
  that menu, not windows of their own.

## Build

Android Studio is not required: JDK 17, the Android SDK and Gradle are.

```
gradle assembleDebug
```

For a release build, create a signing key once and keep it safe (without it no update can be
published):

```
keytool -genkeypair -v -keystore shelf-release.jks -alias shelf -keyalg RSA -keysize 4096 -validity 10000
```

Then put a `keystore.properties` next to `settings.gradle.kts` (it is not in git):

```
storeFile=shelf-release.jks
storePassword=...
keyAlias=shelf
keyPassword=...
```

and run `gradle assembleRelease`.

## Credits

- [OculusDB](https://oculusdb.rui2015.me) by ComputerElite: the store catalog and cover images.
- [LauncherIcons](https://github.com/basti564/LauncherIcons) by basti564: spare cover images.
- Game covers belong to their publishers.
