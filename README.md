<p align="center">
  <picture>
    <source
      width="256px"
      media="(prefers-color-scheme: dark)"
      srcset="assets/revanced-headline/revanced-headline-vertical-dark.svg"
    >
    <img 
      width="256px"
      src="assets/revanced-headline/revanced-headline-vertical-light.svg"
    >
  </picture>
  <br>
  <a href="https://revanced.app/">
     <picture>
         <source height="24px" media="(prefers-color-scheme: dark)" srcset="assets/revanced-logo/revanced-logo.svg" />
         <img height="24px" src="assets/revanced-logo/revanced-logo.svg" />
     </picture>
   </a>&nbsp;&nbsp;&nbsp;
   <a href="https://github.com/ReVanced">
       <picture>
           <source height="24px" media="(prefers-color-scheme: dark)" srcset="https://i.ibb.co/dMMmCrW/Git-Hub-Mark.png" />
           <img height="24px" src="https://i.ibb.co/9wV3HGF/Git-Hub-Mark-Light.png" />
       </picture>
   </a>&nbsp;&nbsp;&nbsp;
   <a href="http://revanced.app/discord">
       <picture>
           <source height="24px" media="(prefers-color-scheme: dark)" srcset="https://user-images.githubusercontent.com/13122796/178032563-d4e084b7-244e-4358-af50-26bde6dd4996.png" />
           <img height="24px" src="https://user-images.githubusercontent.com/13122796/178032563-d4e084b7-244e-4358-af50-26bde6dd4996.png" />
       </picture>
   </a>&nbsp;&nbsp;&nbsp;
   <a href="https://reddit.com/r/revancedapp">
       <picture>
           <source height="24px" media="(prefers-color-scheme: dark)" srcset="https://user-images.githubusercontent.com/13122796/178032351-9d9d5619-8ef7-470a-9eec-2744ece54553.png" />
           <img height="24px" src="https://user-images.githubusercontent.com/13122796/178032351-9d9d5619-8ef7-470a-9eec-2744ece54553.png" />
       </picture>
   </a>&nbsp;&nbsp;&nbsp;
   <a href="https://t.me/app_revanced">
      <picture>
         <source height="24px" media="(prefers-color-scheme: dark)" srcset="https://user-images.githubusercontent.com/13122796/178032213-faf25ab8-0bc3-4a94-a730-b524c96df124.png" />
         <img height="24px" src="https://user-images.githubusercontent.com/13122796/178032213-faf25ab8-0bc3-4a94-a730-b524c96df124.png" />
      </picture>
   </a>&nbsp;&nbsp;&nbsp;
   <a href="https://x.com/revancedapp">
      <picture>
         <source media="(prefers-color-scheme: dark)" srcset="https://user-images.githubusercontent.com/93124920/270180600-7c1b38bf-889b-4d68-bd5e-b9d86f91421a.png">
         <img height="24px" src="https://user-images.githubusercontent.com/93124920/270108715-d80743fa-b330-4809-b1e6-79fbdc60d09c.png" />
      </picture>
   </a>&nbsp;&nbsp;&nbsp;
   <a href="https://www.youtube.com/@ReVanced">
      <picture>
         <source height="24px" media="(prefers-color-scheme: dark)" srcset="https://user-images.githubusercontent.com/13122796/178032714-c51c7492-0666-44ac-99c2-f003a695ab50.png" />
         <img height="24px" src="https://user-images.githubusercontent.com/13122796/178032714-c51c7492-0666-44ac-99c2-f003a695ab50.png" />
     </picture>
   </a>
   <br>
   <br>
   Continuing the legacy of Vanced
</p>

# Expose like status in MediaSession

A ReVanced patch for YouTube that publishes the current video's id and like
status into the media session metadata, so another application can read them
over a `MediaController`.

It was written for a car head unit launcher whose favourite button needs to know
whether the playing video is liked. YouTube does not expose that: unlike Spotify
or Apple Music, it publishes no `Rating` and no like/dislike custom actions, so
any external media client is blind to it.

## What it adds

Three values are written into `MediaMetadata` alongside YouTube's own:

| Key | Type | Meaning |
|---|---|---|
| `android.media.metadata.MEDIA_ID` | String | the 11 character video id |
| `com.android.launcher66.LIKE_EVENT_SEQ` | long | counter, bumped every time a status is published |
| `com.android.launcher66.LIKE_STATUS` | long | `-1` cleared, `0` none, `1` like, `2` dislike |

The video id is the important one. With it a receiving application can ask the
YouTube Data API (`videos.getRating`) for the authoritative status, which is the
only reliable source for a video that was already liked before playback started.

The sequence counter exists so a receiver can tell a real event from a stale
value. It is bumped on every published status, including the reset that happens
when the track changes — so a receiver that restarts mid-video cannot mistake an
old value for a fresh one. Ratings sent *to* YouTube over the media session never
bump it, which lets a receiver ignore the echo of its own actions.

## How it works

Two injection points:

**`MediaSession.setMetadata`** — the metadata is routed through the extension on
its way to the session, which adds the three keys. The target is a framework
class, so this hook is immune to obfuscation and survives YouTube updates.

**The like event constructor** — invoked whenever the user taps the thumb inside
YouTube. This one targets an obfuscated class name and will break on updates; see
`PATCH_MAINTENANCE.md` for how to find the new one. Losing it costs only the
immediate reaction to in-app taps; everything else keeps working.

The video id is obtained by calling ReVanced's own `VideoInformation.getVideoId()`
reflectively. That extension lives in the same APK and its names are kept by the
project's proguard rules, which avoids having to locate the id in obfuscated code.

## Known limitations

**Videos made for kids report no rating.** `videos.getRating` answers `none` for
them however they were rated, and they never appear in `myRating=like`. This is a
limitation on Google's side and cannot be worked around.

**Ratings sent over the media session do not reach the account.** YouTube applies
them to its own interface only — they show up in the app and even in a browser,
but the Data API does not see them and they are absent from the liked videos
list. An application that wants a rating to stick must write it through
`videos.rate` instead, and may then send the session rating afterwards purely to
refresh the on-screen thumb.

**The status of a video liked before playback started is not available here.**
YouTube does not surface it through any Java object at load time; it goes
straight to the native renderer as a protobuf buffer. Six independent attempts to
intercept it failed, which is why the video id is published instead and the
question is left to the Data API.

## Building

Requires JDK 17 or newer and the Android SDK. The patch is built with the
ReVanced patches Gradle plugin:

```
./gradlew build
```

Then applied together with the official bundle, so GmsCore support and the rest
are kept:

```
java -jar revanced-cli.jar patch \
  -p patches.rvp -b \
  -p patches/build/libs/patches-1.0.4.rvp -b \
  --keystore=my.keystore --purge \
  -o youtube-patched.apk stock.apk
```

Last verified against YouTube 20.40.45.

## Consuming the metadata

```java
MediaMetadata metadata = controller.getMetadata();
String videoId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
long seq = metadata.getLong("com.android.launcher66.LIKE_EVENT_SEQ");
long status = metadata.getLong("com.android.launcher66.LIKE_STATUS");
```

Treat a change of `seq` as the event and the accompanying `status` as its value.
Reading `status` on its own is not safe: it is stale after the receiver sends a
rating of its own, because that takes a different path inside YouTube and never
reaches the patch.

## [PATCH_MAINTENANCE](https://github.com/vasyl91/Expose-like-status-in-MediaSession/blob/main/PATCH_MAINTENANCE.md)
