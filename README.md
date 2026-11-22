# Antenna

An Android YouTube app with no ads that also skips sponsor segments automatically.

- **No ads:** videos play directly from YouTube's stream URLs (pulled out with
  [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor)) in ExoPlayer,
  so YouTube's ad-serving player is never loaded. You also don't need a Google account
  and the app does no tracking.
- **Sponsor skipping:** segment times come from the [SponsorBlock](https://sponsor.ajay.app)
  API. The lookup uses the privacy-preserving hash-prefix endpoint, so SponsorBlock never
  learns exactly which video you're watching.
- Search with infinite scroll, a 16:9 player that goes fullscreen when you rotate,
  a choice of which SponsorBlock categories to skip, and a max-quality setting.
- Tap a segment in the list to watch it instead of skipping it.
- **Subscriptions with no account:** tap Subscribe on any video. The Subscriptions tab shows
  the newest uploads from all your channels (pull down to refresh). You can import your
  existing YouTube subscriptions from Google Takeout. Everything is stored on the phone only.
- **Watch history, like YouTube:** a red progress bar under every thumbnail you've started,
  "WATCHED" once you're near the end, automatic resume where you left off, and a History tab.
  You can also hide watched videos from the Subscriptions feed in Settings.
- **Recommendations:** YouTube's own "Up next" list under every video (tap to play, Back
  returns to the previous video), plus a **For you** home tab. It mixes YouTube's suggestions
  for everything you've watched recently, without a Google account (see below).
- Share a YouTube link to Antenna (or open youtube.com / youtu.be links with it) to play it.

## Build & install

1. Install [Android Studio](https://developer.android.com/studio) (current stable version).
2. **File → Open** and pick this `Antenna` folder. Any Gradle JDK from 17 to 25 works,
   including the Java 25 that comes with recent Android Studio versions. The build uses Gradle 9.3.1,
   Android Gradle Plugin 9.1 and Kotlin 2.4. Wait for the Gradle sync to finish
   (it downloads Gradle, the Android SDK pieces and the libraries).
3. On your phone, turn on **Developer options → USB debugging**, plug it in, and press **Run ▶**.

To get an APK you can share: **Build → Build App Bundle(s) / APK(s) → Build APK(s)**.
The file ends up in `app/build/outputs/apk/`. On the phone you'll need to allow
"Install unknown apps" for whatever app you open it with.

From the command line: `./gradlew assembleDebug` (needs `ANDROID_HOME` set or a `local.properties` file with `sdk.dir=...`).

## Project layout

```
app/src/main/java/com/example/antenna/
├── App.kt                        sets up the shared OkHttp client + NewPipe.init
├── net/OkHttpDownloader.kt       HTTP layer used by NewPipeExtractor
├── data/YouTubeRepository.kt     search, pagination, stream info
├── data/Prefs.kt                 settings (skip categories, max quality)
├── data/Subscriptions.kt         local subscription store + Takeout CSV import
├── data/FeedRepository.kt        builds the feed (channel RSS, falls back to the Videos tab)
├── data/WatchHistory.kt          watch progress + saved "up next" lists (local JSON file)
├── data/Recommender.kt           ranks the For you tab from your history
├── player/StreamPicker.kt        picks the best video-only + audio stream and merges them
├── player/YoutubeDataSource.kt   fetches stream bytes the way YouTube expects (POST, rn, range, chunks)
├── sponsorblock/SponsorBlockClient.kt   segment lookup + skip reporting
└── ui/                           Compose screens: search, subscriptions feed, channel list,
                                  settings, player (+ skip loop and Subscribe button)
```

## How "For you" works

YouTube's personalized home feed is tied to a signed-in Google account, and NewPipeExtractor
doesn't support signing in. Signing in would also defeat the privacy point and could get the
account flagged. So Antenna builds its own version from YouTube's recommendations:

1. Each time you open a video, YouTube's "Up next" list for it is saved with your history.
2. The For you tab combines those lists from your last ~30 videos. A video gets ranked higher when:
   it's suggested after several things you watched, it comes from recent or fully watched videos,
   YouTube put it near the top of its list, or it's from a channel you subscribe to.
   Videos you've already watched are left out, and no channel gets more than 3 spots near the top.
3. Pull down to reshuffle a little.

Everything runs on the phone with no extra network requests. To tune it, change the weights in `Recommender.kt`.

## Importing your YouTube subscriptions

1. Go to [takeout.google.com](https://takeout.google.com) and click **Deselect all**.
2. Tick **YouTube and YouTube Music**. Under **All YouTube data included**, keep only **subscriptions**.
3. Export, download the zip, and unzip it. Inside is `YouTube and YouTube Music/subscriptions/subscriptions.csv`.
4. Put that file on your phone. In Antenna, go to **Subscriptions → ✏️ → Import** and choose it.

## When it stops working

YouTube changes its internals often, and third-party clients break when it does. Usually:

1. **Search or "could not get stream" errors** → bump the `NewPipeExtractor` version in
   `app/build.gradle.kts` to the latest
   [release](https://github.com/TeamNewPipe/NewPipeExtractor/releases) and rebuild.
2. **Playback fails with HTTP 403** → YouTube changed how stream bytes have to be requested.
   Compare `player/YoutubeDataSource.kt` with NewPipe's
   [YoutubeHttpDataSource](https://github.com/TeamNewPipe/NewPipe/blob/dev/app/src/main/java/org/schabi/newpipe/player/datasource/YoutubeHttpDataSource.java)
   and port over whatever changed.
3. **ReCaptchaException** → YouTube is rate-limiting your IP. Wait, or switch networks.

## Ideas for next steps

- Background / picture-in-picture playback (a Media3 `MediaSessionService`)
- Channel pages (tap a channel → `ChannelInfo` + its Videos tab), and grouping or filtering Shorts out of the feed
- Caching the feed to disk so it opens instantly, plus a background refresh with new-video notifications (WorkManager)
- Subtitles (`info.subtitles` → `MediaItem.SubtitleConfiguration`)
- A quality picker in the player, and sponsor segments drawn on the seek bar
- "Not interested" on For you items (save a block list of video ids / channels in `Recommender`)
- Autoplay the top Up next video when one ends (`Player.STATE_ENDED` → `openVideo(related.first())`)
- DeArrow (crowdsourced titles and thumbnails, from the same team as SponsorBlock)

## Legal

- Playing YouTube without ads breaks YouTube's Terms of Service, so you can't publish this
  on the Google Play Store. It's for personal use, sideloading, or F-Droid-style distribution.
- NewPipeExtractor is **GPL-3.0**. If you distribute this app, the app has to be GPL-3.0
  too, and you have to publish its source code.
- SponsorBlock data is CC BY-NC-SA 4.0 (non-commercial use only).
