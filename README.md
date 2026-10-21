# Frame

A home screen for Fire TV with two apps on it, Stremio and SmartTube, as plain text over one of your own
photos or a famous painting, and a screensaver that mixes your Immich library with paintings, like an art
mode. 54 KB, no libraries. It talks to your Immich server, four open art collections (the Art Institute of
Chicago, the Met, the Cleveland Museum of Art, and Wikidata/Wikipedia/Commons), and Open-Meteo (for the
weather, if you set a place), and nothing else.

- **Home:** Up/Down to choose, OK to open. Menu (≡) shows Fire TV settings, a screensaver preview, and
  the screensaver's sync status. Back does nothing, so you can't fall out to Amazon's home by accident.
- **Screensaver:** a real Android screensaver (DreamService), started by Fire OS's own timer. Photos
  crossfade and drift slowly, with the place and month in the lower left. Paintings alternate with
  them, shown whole and still with their title, artist and one sentence about them. The time and the
  weather are in the lower right. The newest 80 photos and 60 paintings stay on the TV, so it keeps
  working while the Immich server is off.
- **Fallback:** when Frame has nothing to show (no paintings saved yet, or art turned off, and no photos), the screensaver you had before (Aerial Views on this
  stick) runs instead. See [Fallback](#fallback).

`build/frame.apk` is prebuilt. You only need `adb` to install it.

## Install

Prerequisites on the TV: Settings > My Fire TV > Developer options > **ADB debugging** on. Find its IP
in Settings > My Fire TV > About > Network. On the computer: `adb` (`brew install android-platform-tools`
or `apt install adb`).

```bash
export TV_HOST=192.168.1.50          # or the TV's Tailscale address
./install.sh                         # installs, sets the screensaver, opens Frame on the TV
./install.sh homeonfire              # installs Home on Fire so the Home button opens Frame
./install.sh config http://192.168.1.20:2283 YOUR_API_KEY            # whole library
./install.sh config http://192.168.1.20:2283 YOUR_API_KEY ALBUM_UUID # or one album
./install.sh preview                 # look at the screensaver now
```

You can install before the Immich server is online. Until Frame has photos, your previous screensaver
(Aerial Views) keeps running; within 15 minutes of the server coming up and `config` being run,
Frame takes over.

**Immich API key:** in Immich, Account settings > API keys > New, with the permissions `asset.read` and
`asset.view` only. An album's UUID is the last part of its URL in the Immich web app.

**Home on Fire:** after `./install.sh homeonfire`, on the TV pick Frame as the target, turn on the
accessibility service, and turn on launch at boot. Double-press Home to reach Amazon's home when you
need it.

## Fallback

Frame switches the system screensaver itself, between its own and a fallback. `./install.sh` grants it
the one permission this needs (`WRITE_SECURE_SETTINGS`, the same grant Home on Fire uses). It decides
when the home screen opens, every 15 minutes in the background, and after `config` or `sync`.

The fallback runs when Immich isn't set up, when fewer than 10 photos are saved and Immich can't be
reached, or for an hour after Frame's screensaver failed or crashed. With `./install.sh offline
fallback` it also runs whenever Immich is offline, instead of Frame showing saved photos.

```bash
./install.sh fallback auto     # default: the screensaver set before Frame, else Aerial Views, else Amazon's
./install.sh fallback aerial   # Aerial Views
./install.sh fallback amazon   # Amazon's screensaver
./install.sh fallback sleep    # no screensaver: the stick sleeps and the LG takes over
./install.sh fallback none     # Frame stays and shows what's wrong
```

**If Frame's screensaver starts and finds nothing** (it has no photos and the server went down since the
last check), it gives up within about 5 seconds. With Aerial Views as the fallback it starts Aerial
through Aerial's own handoff activity (`TestActivity`, exported by Aerial for third-party apps). The
picture may go black for a moment during the switch: Android turns the screen off when one
screensaver ends after the idle timeout, and Aerial turns it back on. With other fallbacks the stick
goes to sleep that time. Either way Frame switches the setting, so the next idle starts the fallback
directly.

**Why "sleep" for the LG:** an HDMI stick can't start the LG's screensaver; webOS runs that for its own
apps and decides by itself when to show it. What the stick can do is stop sending a picture. What the
LG does then depends on its settings. If SIMPLINK (HDMI-CEC) is on and Fire TV's HDMI CEC device
control is on, the stick going to sleep can switch the TV off too. With CEC off, the TV shows its
no-signal screen and turns itself off after its Auto Power Off time (General > Power or Timers, by
webOS version). Try it with `fallback sleep` and see which you prefer.

## Doing it with Claude Code

Open this folder in Claude Code on a computer on the same network as the TV and paste:

> Install Frame on my Fire TV Stick 4K Max (Fire OS 7.7.1.3). Find the TV on my network (hostname,
> ARP table, or ask me for the IP) and connect over ADB on port 5555. If I give you a Tailscale
> address, use that and don't scan my network. Use `./install.sh` for every step, and after each one
> take `./install.sh shot` and look at it to check the TV shows what it should. Then set up Home on
> Fire. Check that my Aerial Views screensaver is still the one in use until Immich is set up
> (`./install.sh status`). If the screensaver setting doesn't stick or Home doesn't redirect, follow Troubleshooting in
> README.md. My Immich server is at `<url>` and the API key is `<key>`; if it's offline, skip
> `config` and tell me.

## Change things

- **Apps:** `res/values/apps.xml`. Each entry can list several package names separated by `|`; the
  first one installed is used. Run `adb shell pm list packages` to find names.
- **Art:** on by default. `./install.sh art off` turns it off (the screensaver then works as described in
  [Fallback](#fallback)); `art on` turns it back on and saves some paintings at once. About 860 notable
  public-domain paintings: the highlighted paintings of the Art Institute of Chicago, the Met and the
  Cleveland Museum of Art, and the paintings made before 1930 with articles in at least 20 Wikipedias
  (Mona Lisa, The Starry Night, Girl with a Pearl Earring...), captioned from Wikipedia. Works are saved
  in turn from each source and shrunk on the TV to 1920 x 1080; 60 stay, and four new ones rotate in each
  day. Violent scenes (battles, executions, crucifixions, martyrdoms, dead bodies, weapons drawn) and
  sexually explicit works are left out, by the collections' subject tags and words in the title,
  caption and Wikipedia summary. The sources are in `ArtSources.java`, the filter in `ArtStore.java`.
- **Weather:** `./install.sh weather 94555` (a US ZIP code; add `celsius` for °C), `weather off` to hide it.
  The ZIP is looked up on the computer; the TV only stores the coordinates and asks Open-Meteo for the
  current temperature every 20 minutes at most.
- **Background before Immich:** `./install.sh import backgrounds` uses the `.jpg`/`.png` files in
  `backgrounds/` as the home screen's background while no Immich photos are saved. They don't count
  as screensaver photos, so the screensaver still falls back as described in [Fallback](#fallback).
- **Seconds per photo:** `./install.sh interval 30` (5 to 600, default 20).
- **Look:** `HomeActivity.java` and `Slideshow.java`; colors and sizes are at the top of each file.
- **Rebuild:** `./build.sh`, then `./install.sh`. It uses the Android SDK if one is installed (Android
  Studio isn't needed), or Ubuntu's `aapt dalvik-exchange zipalign apksigner` packages plus an
  `android.jar` in `ANDROID_JAR`. Every build is signed with `signing/frame.jks`, so updates install
  over the old version and keep your settings and saved photos. That key only protects this app on
  your own TV; it is not a secret.

## How it works on Fire OS 7, and what it can't do

- **The Home button.** Fire OS sends Home to Amazon's launcher no matter what, and only system-signed
  apps can intercept it. Home on Fire (an accessibility service) watches for Amazon's home and opens
  Frame over it. On Fire OS 7 that takes about **5 seconds**: Android 9 holds back app switches from
  the background for 5 s after Home is pressed, and only system apps are exempt. Home on Fire covers
  the wait with a loading screen. Fire OS 8 switches in about 250 ms.
- **The screensaver** is picked with the secure settings `screensaver_components` and, on Fire OS
  7.6 and later, `screensaver_default_component` (both are written). Opening Amazon's screensaver
  settings can put Amazon's back; Frame puts the right one back at its next check.
- **The config receiver** only accepts broadcasts from callers holding `DUMP`, which on a normal TV
  means the ADB shell. Other apps on the TV can't change the server or read the key.
- **Not included:** debloating Amazon packages. It is independent of this app, and disabling the wrong
  package (Amazon's launcher also hosts Settings) can lock you out of Settings. If you want it later,
  `roy9611/firetv-safe-debloat` targets Fire OS 7 specifically.

## Troubleshooting

| What you see | What to do |
|---|---|
| `install.sh` warns the screensaver setting didn't stick | Run `adb shell settings get secure screensaver_components`. If it's still Amazon's, Fire OS 7.7.1.3 is resetting it. `adb shell dumpsys dreams` shows which screensaver Android will actually start. The home screen works either way. |
| `status` shows `WRITE_SECURE_SETTINGS: granted=false` | Fire OS refused the grant, so Frame can't switch screensavers; `install.sh` then sets Frame directly, and the fallback only works on the spot (Aerial handoff). Run `adb shell pm grant com.framehome android.permission.WRITE_SECURE_SETTINGS` and read the error. |
| Aerial Views runs even though Immich works | Run `./install.sh status`: the last line says why. After a failure Frame waits an hour; `./install.sh sync` clears that at once. |
| TV turns off instead of showing a screensaver | The fallback is `sleep` and HDMI-CEC is switching the TV off with the stick. Turn off SIMPLINK on the LG or HDMI CEC device control on the Fire TV, or use `fallback aerial`. |
| Screensaver never starts | Settings > Display & Sounds > Screensaver > Start time must not be Never. `./install.sh dream` tries to start it immediately. |
| "Immich rejected the API key (HTTP 401)" | Wrong key, or it was deleted. Run `config` again with a new one. |
| "The API key needs ... (HTTP 403)" | Edit the key in Immich and add `asset.read` and `asset.view`. |
| "Album ... has no photos this API key can see" | The album belongs to someone else and isn't shared with the key's user. |
| Home still goes to Amazon's home | Open Home on Fire and check its service switch is on. `./install.sh status` shows the enabled accessibility services. If Fire OS won't keep it on, Home on Fire's XDA thread has Fire OS 7 builds and logs to compare. |
| SmartTube or Stremio says "isn't installed" | The package name differs. `./install.sh` lists what it found; put that name in `res/values/apps.xml` and rebuild. |

`./install.sh status` prints the settings and the app's last log lines (tag `Frame`).

## Undo

`./install.sh remove` puts back the screensaver you had before (both settings) and uninstalls Frame. Uninstall Home on
Fire from Settings > Applications.

## Sources

- Paintings: [Art Institute of Chicago API](https://api.artic.edu/docs/) (`is_public_domain`, `is_boosted`,
  IIIF images; the museum asks for an `AIC-User-Agent` header, which Frame sends),
  [The Met Collection API](https://metmuseum.github.io/) (search v1.1 with `isHighlight`; v1 search was
  retired on 2026-10-01), [Cleveland Museum of Art Open Access API](https://openaccess-api.clevelandart.org/)
  (`cc0`, `highlight`), [Wikidata Query Service](https://query.wikidata.org/) (paintings by number of
  Wikipedia sitelinks) with [Wikipedia's REST summary](https://en.wikipedia.org/api/rest_v1/) for captions.
- Weather: [Open-Meteo](https://open-meteo.com/en/docs) forecast `current` values and its geocoding API,
  with WMO weather codes for the icon.

- Aerial Views' component name, its Fire OS 7.6+ `screensaver_default_component` note, and its
  dream-to-activity handoff (`DreamActivity.kt`, `TestActivity.kt`):
  [theothernt/AerialViews](https://github.com/theothernt/AerialViews).
- LG OLED screensaver behavior for webOS apps: [webOS TV developer guide, Screensaver](https://webostv.developer.lge.com/develop/guides/screensaver).
- Fire TV screensaver via `screensaver_components` and the Amazon default:
  [itsmeterrylin/fire-tv-cleanup](https://github.com/itsmeterrylin/fire-tv-cleanup) (tested on Fire OS 8).
- Home redirect, Fire OS 7 delay and why: [toolicious/home-on-fire](https://github.com/toolicious/home-on-fire)
  and its [XDA thread](https://xdaforums.com/t/app-firetv-noroot-home-on-fire-use-any-launcher-as-your-fire-tv-home-home-button-boot-fire-os-8-7.4791567/page-2).
- Gradle-free build and boot-order notes: [whyashthakker/debloat-fire-stick-claude-code](https://github.com/whyashthakker/debloat-fire-stick-claude-code) (`firehome/`).
- Immich API shapes, checked against the v3.3.3 OpenAPI spec:
  [immich-openapi-specs.json @ v3.3.3](https://github.com/immich-app/immich/blob/v3.3.3/open-api/immich-openapi-specs.json),
  with notes from [DeRusto/goCasa#5](https://github.com/DeRusto/goCasa/issues/5). Known Immich issues
  worked around: `/search/random` skews toward low UUIDs
  ([#26049](https://github.com/immich-app/immich/issues/26049)) and misses other users' photos in
  shared albums ([#30585](https://github.com/immich-app/immich/issues/30585)), so albums are listed
  with `/search/metadata` instead.
