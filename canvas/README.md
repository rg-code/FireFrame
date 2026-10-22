# Canvas

Frame's idea for a Hisense CanvasTV with Google TV. Canvas doesn't replace Art Mode, it adds three
things to it:

- **Two small icons over Art Mode.** Stremio and SmartTube sit in the lower right corner as one-color
  icons on a faint dark pill, whenever Art Mode is on screen. Art Mode keeps the remote. Only **Down**
  is taken: it moves focus onto the icons. Then **Left/Right** choose, **OK** opens, and **Back**, **Up**
  or 8 idle seconds give the remote back. Any other key goes to Art Mode as usual.
- **Public-domain paintings in Art Mode's photo collection.** These are the same ~860 notable paintings as
  Frame's (Art Institute of Chicago, the Met, Cleveland Museum of Art, Wikidata/Wikipedia), with the
  same filter for violent and explicit works. Each is saved as a 3840 x 2160 JPEG with the painting
  whole on a plain mat and a small label (title, artist) under it, so Art Mode can't crop a tall
  painting. 60 stay, and four new ones rotate in each day. Canvas only touches its own files
  (`canvas-*.jpg`). Your own photos in the collection are left alone.
- **Art Mode in the evening, when someone is home.** From 18:00 to 20:00 the TV turns on into Art Mode.
  If the TV's presence (motion) sensor sees nobody for 10 minutes, Art Mode turns off again, and it
  looks again 30 minutes later. At 20:00 Art Mode turns off. It never interrupts the TV while it's in
  use (another app on screen). If you turn the TV off with the remote during the evening, it rests
  until the next evening.

No libraries. The only servers it talks to are the four art collections.

`build/canvas.apk` is prebuilt. You only need `adb` to install it.

## Install

On the TV: Settings > System > About, press OK on **Android TV OS build** 7 times. Then in Settings >
System > Developer options, turn on **USB debugging** and network debugging (named "Wireless
debugging" or "ADB over network" on some builds). The TV's IP is in Settings > Network & Internet.
Install Stremio and SmartTube first (Play Store or Downloader).

```bash
export TV_HOST=192.168.1.60
./install.sh            # installs; turns on the overlay service, the screen-off permission and storage
# now open Art Mode on the TV (press the remote's power button, or open the Art Mode app), then:
./install.sh learn      # remembers Art Mode, lists its screens and folders that may hold its photos
./install.sh findphotos # after adding one photo to Art Mode's collection from the phone app
./install.sh photos /sdcard/<the folder findphotos showed>
./install.sh status
```

`learn` has to run once, with Art Mode on screen, because Hisense doesn't document Art Mode's package
name. It decides when the icons show and what the evening schedule opens.

## Photo collection

Canvas needs the folder Art Mode reads its photo collection from. The surest way to find it: add one
photo to the collection from Hisense's phone app, run `./install.sh findphotos` within 30 minutes, and
it prints where the photo landed and the `photos` command to use. Until then, paintings go to
`/sdcard/Pictures/Canvas Art`. Every new or deleted file is also reported to Android's media index,
for galleries that read the index instead of the folder.

If `findphotos` finds nothing, Art Mode keeps its photos in its own private storage, and no other app
can add to it there. Then the way in is a USB stick, if your Art Mode can show photos from USB. Copy
the paintings off the TV (`adb pull /sdcard/Pictures/Canvas\ Art`) onto the stick.

```bash
./install.sh art sync        # save 12 more now (the background job saves the rest, every 3 hours)
./install.sh art off         # delete Canvas's paintings and stop
./install.sh mat '#1E1D1B'   # a dark mat (the labels turn light); mat off saves the painting alone
```

## Evening schedule and the presence sensor

```bash
./install.sh schedule 18:00-20:00   # or off; a window past midnight (22:00-01:00) works too
./install.sh away 10                # minutes with nobody seen before Art Mode turns off
./install.sh retry 30               # then minutes before it turns on and looks again
./install.sh sensors                # every sensor the TV reports, and which one Canvas uses
./install.sh sensor 'NAME'          # pick one by its exact name; auto (default) or off
./install.sh start | sleep | tick   # try it now: Art Mode on, off, or one schedule check
```

**The sensor.** The CanvasTV's motion sensor is Hisense hardware. Hisense says it only works in Art Mode,
and it may not be visible to other apps at all. With `auto`, Canvas uses the TV's proximity sensor
if there is one, or else a sensor whose name says motion, presence, PIR, human, body, occupancy or radar.
A proximity sensor counts as someone there when it reads nearer than its range, any other sensor when
it reads non-zero. Run `./install.sh sensors` once to see what's there. If nothing usable shows up,
the schedule still runs, and Art Mode stays on for the whole evening. Hisense's own motion setting
(Art Mode settings, "motion detection") can still turn the screen off when the room is empty. Canvas
counts that as the TV being turned off and rests until the next evening.

Because the sensor may only work during Art Mode, finding out whether anyone is home means turning Art
Mode on for `away` minutes. That's why an empty house sees Art Mode for 10 minutes every 30.

**Turning on and off.** To turn the TV on, Canvas uses a wake lock that turns the screen on and opens
Art Mode. This works when the TV is in standby (Android asleep), which is how Google TV sets usually
turn off. If the TV was unplugged, or Google TV's energy settings shut it down fully, nothing runs
until it starts again. To turn it off, Canvas uses Android's `lockNow`, which sleeps the TV like the
power button. That needs the device admin `install.sh` turns on.

## Change things

- **Apps:** `res/values/apps.xml`, as in Frame. `app_icons` names the glyph drawn in `AppGlyph.java`.
- **Focus key:** `./install.sh key DPAD_UP` (or `MENU`, `INFO`...) if Art Mode needs Down for itself.
- **Look:** sizes and colors at the top of `Overlay.java` (icons) and `Mat.java` (mat and label).
- **Rebuild:** `./build.sh`, then `./install.sh`. It needs the same tools as Frame's build. It signs with
  Frame's key (`../signing/frame.jks`), so updates install over the old version.

## How it works, and what it can't do

- **The icons** come from an accessibility service (`Overlay.java`). It's the one kind of sideloaded
  app that can see which app is on screen, draw over it (`TYPE_ACCESSIBILITY_OVERLAY`, no "display
  over other apps" permission) and see remote presses before Art Mode does. `install.sh` turns it on
  with `settings put secure enabled_accessibility_services`. If Art Mode is a screensaver (dream) on
  your firmware rather than an app, `learn` says so. The icons may still show, but the schedule can't
  start it.
- **Starting apps from the background** is allowed because the accessibility service is on. If you
  turn it off in Settings > Accessibility, the icons and the schedule stop.
- **Storage:** Canvas targets Android 10 (API 29) with legacy external storage, so it can write to a
  shared folder with the plain storage permission `install.sh` grants.
- **Not tested on a CanvasTV yet.** This was written without the TV at hand, so Art Mode's package, its
  photo folder, and whether Hisense's sensor is visible to apps are found on the TV by `learn`,
  `findphotos` and `sensors`. Hisense's Art Mode might also draw its pictures on a video layer above
  apps, which would hide the icons. `./install.sh shot` with Art Mode on shows what's there.

## Troubleshooting

| What you see | What to do |
|---|---|
| No icons over Art Mode | `./install.sh status`: the first line says whether the overlay service is on, and "on screen now" must match the Art Mode line. If they differ (Art Mode moved to another screen), run `learn` again with Art Mode showing. |
| Down does something in Art Mode you need | `./install.sh key DPAD_UP` (or another key). |
| "Can't write there" in status | `./install.sh` again (it grants storage), or pick a folder under `/sdcard`. |
| Paintings saved but not in Art Mode | Art Mode reads another folder, or only its own uploads. See [Photo collection](#photo-collection). |
| TV doesn't come on at 18:00 | `./install.sh status` shows the next check and why it's resting. Then `./install.sh start` with the TV off checks that turning on works on your firmware. `adb logcat -s Canvas` shows every decision. |
| Art Mode turns off with people in the room | The sensor isn't seeing them: `./install.sh sensors`, then `sensor off` to keep Art Mode on all evening, or a longer `away`. |
| `remove` says the app can't be uninstalled | The device admin is still on: Settings > Security > Device admin apps, turn Canvas off, then `adb uninstall com.canvashome`. |

## Undo

`./install.sh remove` deletes Canvas's paintings, turns off its accessibility service and device admin,
and uninstalls it. Art Mode and your own photos stay as they were.
