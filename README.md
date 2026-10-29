# Edge Light

Samsung-style **edge lighting** for Pixel phones, with a **Wear OS companion**. A glowing, animated border runs around the screen edge when a notification arrives, a call rings, or anything else you define, and a rules engine lets you change the colours and animation per app, per sender, per time of day and more.

It is deliberately tiny: the phone APK is about **160 KB** and the watch APK about **75 KB**. There is no Compose, no AppCompat and no Material Components library. The UI is built from plain platform views styled with Material 3 dynamic colour.

> Not affiliated with Samsung. "Edge Lighting" is Samsung's name for the feature this project imitates.

Made by [Kieran Davies](https://github.com/kierandavies06/).

## Features

- **Seven effects**: sweep, chase, rainbow, pulse, breathe, strobe, solid. Each has its own colours (with a real colour picker), width, cycle duration, repeats and glow.
- **Rules engine**: override any part of the effect depending on:
  - which app (or *not* which app) the notification is from
  - channel, category, title / text / "anywhere in the notification" regexes
  - importance, time window, days of the week
  - whether the phone is charging, the screen is on, or Do Not Disturb is active
  - Rules apply top to bottom and *merge*: each matching rule overrides only the fields it sets. A rule can also suppress the light or stop evaluation.
- **Incoming calls** *(experimental)*: the light loops for as long as the phone is ringing, driven by the real call state, and stops when you answer, decline or miss it. VoIP calls with a full-screen incoming-call notification are handled too.
- **Over the lock screen** (via an accessibility overlay) and optional **wake screen** for idle displays.
- **Pixel Watch companion**: settings and rules sync from the phone and the same lights play on the watch, including waking it from ambient mode. Crown scrolling supported.
- **Private**: the apps request no internet permission and contain no analytics or tracking. Phone-to-watch sync goes through Google's Data Layer, which handles the transport; this project never touches the network itself.

## Requirements

- Android 12 or newer (minSdk 31). Developed and tested on a **Pixel 9** and **Pixel Watch 4**; other devices may work but are untested.
- The watch app needs Wear OS with Google Play services (for the Data Layer).

## Install

Download the two APKs from the [Releases](../../releases) page (or build them, below) and install both:

```sh
adb install -r edge-light-phone.apk
adb -s <watch-address> install -r edge-light-watch.apk   # optional
```

Open the app and grant, via the status pills at the top:

| Pill | What it does | Where |
| --- | --- | --- |
| Notifications | Notification access, so the app can see notifications | Settings → Notification access |
| Overlay | "Display over other apps", to draw the light | Settings → Apps → Special access |
| Lock screen | Accessibility overlay so the light also draws over the lock screen | Settings → Accessibility |
| Calls | `READ_PHONE_STATE`, to follow the real ringing state | Permission prompt |

Or grant everything over adb (handy for the watch, which has no easy settings UI):

```sh
PKG=uk.me.kierandavies.pixeledge
adb shell appops set $PKG SYSTEM_ALERT_WINDOW allow
adb shell cmd notification allow_listener $PKG/$PKG.Svc
adb shell pm grant $PKG android.permission.READ_PHONE_STATE
adb shell pm grant $PKG android.permission.WRITE_SECURE_SETTINGS
adb shell settings put secure enabled_accessibility_services $PKG/$PKG.Acc
```

Notes:

- The accessibility service exists only to host the overlay window above the lock screen. It declares no event types and cannot read screen content. It is flagged as an accessibility tool so it can run on devices using Android's *Advanced Protection*, which otherwise blocks non-assistive accessibility services.
- Reinstalling or force-stopping the app can switch the accessibility service and notification listener off again; re-grant as above.
- Phone notifications are *not* visible to apps on the watch (Wear OS handles them in system UI), so the **phone** decides when to fire and tells the watch what to play.

## Rules

Open **Rules → Add rule**. A rule has *conditions* (all must match) and an *action*.

| Condition | Meaning |
| --- | --- |
| From apps / Not from apps | Package names (pick from the installed apps) |
| Channel contains | Substring of the notification channel id |
| Category | Notification category, e.g. `msg`, `email`, `call`, `social`, `promo`, `alarm` |
| Title / Text matches regex | Case-insensitive |
| Anywhere matches regex | Title, text, expanded text, sub-text and message lines together |
| Minimum importance | Any / Low+ / Default+ / High |
| Time window, days | Local time; a window such as 22:00 → 07:00 wraps past midnight |
| Charging, Screen on, Do Not Disturb | Any / Yes / No |

The action can override any of style, colours, width, duration, repeats and glow (leave a control on "inherit" to keep the default), suppress the light entirely, and/or stop evaluating later rules.

### Import rules from a file

Rules are stored as JSON in the app's private storage. To load rules without using the UI, push a file where the app will find it, then open the app. It merges rules by name (replacing a rule with the same name) and deletes the file.

```sh
adb push import.json /sdcard/Android/data/uk.me.kierandavies.pixeledge/files/import.json
```

```json
{
  "rules": [
    {
      "name": "Mentions of Alex",
      "on": true,
      "notPkgs": ["com.facebook.katana"],
      "anyRe": "\\b(alex|alexander)",
      "fx": { "style": "strobe", "colors": [-59580, -28416], "repeats": 3 }
    }
  ]
}
```

Keys: `name`, `on`, `pkgs`, `notPkgs`, `anyRe`, `channels`, `cats`, `titleRe`, `textRe`, `minImp` (0, 2, 3, 4), `from` / `to` (minutes since midnight), `days` (bitmask, bit 0 = Sunday), `charging` / `screen` / `dnd` (0 any, 1 yes, 2 no), `suppress`, `stop`, and `fx` with `style`, `colors` (signed ARGB ints), `width` (dp), `duration` (ms), `repeats`, `glow`.

## Build from source

Linux x86_64. No Gradle: a small shell script drives `aapt2`, `kotlinc`, R8 and `apksigner`.

```sh
scripts/setup.sh        # downloads JDK 21, Android SDK 35, Kotlin and the Data Layer client (~35 s)
./build.sh app          # -> build/app/edgelight.apk
./build.sh watch        # -> build/watch/edgelight.apk
```

Layout:

```
shared/   code used by both apps: data model, rules engine, notification listener, overlay renderer, call-ring logic
app/      phone app: Material 3 UI, rule editor, colour picker, watch sync (sender)
watch/    Wear OS app: status screen, watch sync (receiver)
scripts/  toolchain setup
.github/  CI: build on every push, signed release on tags
```

### Build info

Each build stamps its version, build number (git commit count), short commit hash (with `-dirty` if there are uncommitted changes) and build time into the app. They appear in the **About** card on the phone and under the Test button on the watch, so you can tell exactly which build each device is running. Outside a git checkout the commit shows as `dev`. Override with `VERSION`, `VERSION_CODE` and `COMMIT` environment variables.

### Signing

Every APK must be signed to install. **The phone and watch apps must be signed with the same key** (and share the package name), otherwise the Data Layer will not connect them.

- By default `build.sh` signs with a throwaway **debug key** it creates in `.toolchain/`. That is fine for trying things out, but each fresh checkout gets a *different* key, so a build from another machine can't be installed over yours without uninstalling first.
- For anything you intend to keep, create one release key once and always build with it:

  ```sh
  keytool -genkeypair -keystore release.jks -alias edge -keyalg RSA -keysize 2048 -validity 36500
  KEYSTORE=release.jks KEY_ALIAS=edge KS_PASS=... ./build.sh app    # and ./build.sh watch
  ```

  Back the keystore up somewhere safe and never commit it (`*.jks` and `*.keystore` are git-ignored). If you lose it, you can't ship updates that install over existing copies.
- Forks that want to coexist with the original should change the package name (`uk.me.kierandavies.pixeledge`) in both manifests and the source.

### Continuous integration

- `.github/workflows/build.yml` builds both apps on every push and pull request and uploads **debug-signed** APKs as an artifact.
- `.github/workflows/release.yml` runs when you push a tag like `v1.0.0`: it builds with your release key and publishes the APKs plus `SHA256SUMS` to a GitHub release. Add three repository secrets first: `KEYSTORE_BASE64` (`base64 -w0 release.jks`), `KS_PASS` and `KEY_ALIAS`.

## Limitations

- **No always-on-display (AOD)**: Android renders AOD in a low-power state that no third-party window can reach without root. The "Wake screen" option is the closest substitute.
- The build tooling is Linux only.

## License

[MIT](LICENSE). The source in this repository is yours to use however you like. The built APKs also bundle Google's Play Services Data Layer client libraries (fetched by `scripts/setup.sh`, not stored in the repo), which remain under Google's own terms.