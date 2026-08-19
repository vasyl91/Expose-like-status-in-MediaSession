# Maintaining the "Expose like status in MediaSession" patch

This patch publishes two things into YouTube's media metadata so a launcher can
read them over a `MediaController`:

| Key | Meaning1 |
|---|---|
| `android.media.metadata.MEDIA_ID` | the 11 character video id |
| `com.android.launcher66.LIKE_EVENT_SEQ` | counter, bumped on every published status |
| `com.android.launcher66.LIKE_STATUS` | `-1` cleared, `0` none, `1` like, `2` dislike |

It has exactly two injection points, and only one of them is fragile.

## Which hook breaks, and which does not

**Hook B — `MediaSession.setMetadata`.** Targets `android.media.session.MediaSession`,
a framework class. Its name can never be obfuscated, so this hook survives
YouTube updates indefinitely. If it ever fails, the cause is architectural (YouTube
stopped using the platform media session), not a rename.

**Hook A — the like event constructor, currently `Liyl;`.** This is an obfuscated
name from YouTube's own code and **will** change, typically every few releases.
This is the one you will be fixing.

Losing hook A costs only the immediate reaction to a like made inside the
YouTube app. The video id keeps flowing, so a launcher that resolves the status
through the YouTube Data API keeps working.

## Recognising the breakage

The patch fails loudly on purpose. During patching you will see:

```
Expose like status - metadata hooks: 2
SEVERE: "Expose like status in MediaSession" failed:
app.revanced.patcher.patch.PatchException: Constructor of Liyl; not found.
The class was likely renamed by a YouTube update - search the APK for INDIFFERENT.
  candidate: Labc;
```

Note the counters printed before the failure. `metadata hooks: 2` means hook B
still found its target, so only hook A needs attention.

A subtler symptom: the patch applies, but the launcher never reacts to likes
made in the app. That means the constructor matched a class that is no longer
the like event. Verify with logcat — `LikeStatusHook` logs nothing on a tap.

## Finding the new class name

You need jadx-gui and the exact `stock.apk` you are patching.

### Step 1 — locate the status enum

Search the decompiled APK for the string `INDIFFERENT` (Search → Text). The word
is rare and appears in a small generated enum:

```java
public enum azev implements ateq {
    LIKE(0),
    DISLIKE(1),
    INDIFFERENT(2);
    ...
}
```

The class name (`azev` in this example) changes between versions. Write it down.

Two properties of this enum matter and have held across every version so far.
The ordinals are `LIKE=0, DISLIKE=1, INDIFFERENT=2` — different from the
launcher's own constants, so check them rather than assuming. And `toString()`
returns the bare ordinal, which is what lets the extension read the value
without referencing the obfuscated type at all.

### Step 2 — find the event class

Use Find Usages on the enum and look for a **constructor** whose signature is
`(String, <enum>, boolean)`. In the version this patch was written against it
looked like this, reached from `mrz.m63828i`:

```java
((aate) obj2).m1626c(new iyl(azewVar3.f86530c, azevVar, z));
```

`iyl` is the class you want. Confirm it by checking that:

- the first parameter is a `String` that holds an 11 character video id
- it is constructed on the like path, not on a rendering path
- it is also referenced by a listener class (in this version, `lsq.mo833gE`)

If Find Usages returns many candidates, filter by the constructor signature —
three parameters in that exact order is a strong signal.

### Step 3 — write the descriptor

jadx displays these classes under `package p000`. **That package does not
exist.** It is a placeholder jadx invents for the default package, because Java
cannot import from it. In the dex the class has no package prefix at all:

```
jadx shows:  p000.iyl
descriptor:  Liyl;
```

Getting this wrong is the single most common mistake when updating this patch.

### Step 4 — update the patch

In `ExposeLikeStatusPatch.kt`:

```kotlin
private const val LIKE_EVENT_CLASS = "Liyl;"
```

Rebuild and check the counters:

```
Expose like status - metadata hooks: 2
Expose like status - like event hooks: 1
```

Both must be non-zero.

## Verifying on the device

```
adb logcat -c
```

Play a video, tap the thumb inside the YouTube app, then:

```
adb logcat -d | grep "In-app like event"
```

You want a line per tap, with the sequence number rising:

```
In-app like event #12: status=1 -> FAVORITED for dQw4w9WgXcQ
```

Three failure modes and what they mean:

- **No lines at all.** The constructor matched a class that is not on the like
  path. Go back to step 2.
- **Lines appear, but `status` is always the same.** The parameter index is
  wrong. The patch injects `p1, p2`; if the constructor gained a parameter, the
  enum may now be `p3`. Check the signature in jadx.
- **`NoClassDefFoundError` in logcat.** The extension package path and the
  descriptor in the patch have drifted apart. They must match exactly:
  `app/revanced/extension/youtube/LikeStatusHook` on both sides.

## If the class cannot be found at all

YouTube may restructure the like path so no such constructor exists. Before
spending time on it, weigh what hook A actually buys you: an instant reaction to
a tap made inside the app. Everything else — the video id, the status resolved
through the Data API, ratings sent by the launcher — works without it.

A reasonable fallback is to make hook A optional: replace the `PatchException`
with a warning so the patch still applies with hook B alone.

```kotlin
        println("Expose like status - like event hooks: $likeHooks")
        if (likeHooks == 0) {
            println("  warning: the like event class was not found, in-app taps " +
                "will not be reported. Everything else still works.")
        }
```

Do not do this silently. A patch that quietly does half its job is worse than
one that fails.

## Things that look like breakage but are not

**`madeForKids` videos never report a rating.** YouTube returns `none` from
`videos.getRating` for them however they were rated, and omits them from
`myRating=like`. This is a limitation on Google's side, not a patch problem.

**Ratings sent over the media session do not reach the account.** They update
YouTube's own interface only. The launcher writes through `videos.rate` for that
reason, and sends the session rating afterwards purely to refresh the on-screen
thumb.

**The counter jumps by more than one.** Expected. It is bumped for every
published status, including the reset on a track change, not only for user taps.

## Checklist for a version bump

1. Get the new `stock.apk` and confirm the version is supported by the official
   patch bundle: `list-patches -p patches.rvp -b --versions`
2. Build and patch; read the two counters
3. If hook A failed, follow steps 1 to 4 above
4. Verify on the device with the logcat check
5. Confirm the video id still arrives: the launcher should log
   `getRating(<id>) raw:` shortly after a track starts
