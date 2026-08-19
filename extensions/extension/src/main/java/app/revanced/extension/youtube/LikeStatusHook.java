package app.revanced.extension.youtube;

import android.media.MediaMetadata;
import android.media.Rating;
import android.media.session.MediaSession;
import android.util.Log;

/**
 * Publishes the like status of the current video into MediaMetadata so that
 * external apps can read it through a MediaController.
 *
 * Two values are added on top of YouTube's own metadata:
 *
 *   METADATA_KEY_USER_RATING - a thumb Rating, updated whenever the user
 *       likes or unlikes the video inside the app.
 *   METADATA_KEY_MEDIA_ID    - the 11 character video id, which lets the
 *       receiving app resolve the authoritative like status through the
 *       YouTube Data API (videos.getRating).
 *
 * Known limitation: the status of a video that was already liked before
 * playback started is not available here. YouTube does not surface it through
 * any Java object at load time - it goes straight to the native renderer as a
 * protobuf buffer. That is why the video id is published: resolving the state
 * is left to the receiving app, which can ask the Data API.
 */
public final class LikeStatusHook {

    private static final String TAG = "LikeStatusHook";

    private static final boolean DEBUG = false;

    /**
     * Custom metadata keys. MediaMetadata.Builder only validates keys it knows
     * about, so application specific ones pass through untouched - YouTube
     * itself does the same with its video dimension keys.
     *
     * The sequence number is what makes the status usable by a receiver. It is
     * bumped on every like interaction inside the app, so a receiver can tell
     * an actual event apart from a stale value, and can ignore ratings it sent
     * itself: those never bump the counter.
     */
    public static final String KEY_LIKE_EVENT_SEQ =
            "com.android.launcher66.LIKE_EVENT_SEQ";
    public static final String KEY_LIKE_STATUS =
            "com.android.launcher66.LIKE_STATUS";

    /** Published when the status was cleared rather than chosen by the user. */
    public static final int UNSET = -1;

    public static final int NONE = 0;
    public static final int LIKE = 1;
    public static final int DISLIKE = 2;

    // Values of YouTube's "azev" enum. Note the order differs from the
    // constants above: azev is LIKE, DISLIKE, INDIFFERENT.
    private static final int AZEV_LIKE = 0;
    private static final int AZEV_DISLIKE = 1;
    private static final int AZEV_INDIFFERENT = 2;

    private static volatile int likeStatus = NONE;
    private static volatile long likeEventSeq = 0L;
    private static volatile MediaSession session;
    private static volatile MediaMetadata lastMetadata;
    private static volatile String currentTitle;
    private static volatile String currentVideoId;

    private static volatile java.lang.reflect.Method videoInformationMethod;
    private static volatile boolean videoInformationFailed;

    private LikeStatusHook() {
    }

    /**
     * Hook B: called immediately before MediaSession.setMetadata.
     *
     * Clears the cached status on a track change, because hook A only fires
     * when the user interacts, never when a video is loaded.
     *
     * @return the metadata to publish, enriched with the rating and video id
     */
    public static MediaMetadata addRating(MediaSession mediaSession, MediaMetadata metadata) {
        session = mediaSession;

        String title = metadata == null
                ? null
                : metadata.getString(MediaMetadata.METADATA_KEY_TITLE);

        // Empty titles are skipped: YouTube publishes metadata in waves and
        // the first wave is often incomplete.
        if (title != null && title.length() > 0 && !title.equals(currentTitle)) {
            currentTitle = title;
            // The sequence is bumped here too. It identifies a published
            // value, not just a user interaction: without this, the same
            // number would describe a like before the track changed and a
            // cleared status afterwards, and a receiver that restarted in
            // between would apply the stale one.
            //
            // UNSET rather than NONE, because a new track means the status is
            // unknown, not that the video is unrated. A receiver able to look
            // the real status up can then wait for it instead of showing a
            // guess.
            likeStatus = UNSET;
            likeEventSeq++;
            if (DEBUG) {
                Log.d(TAG, "reset on track change: " + title);
            }
        }

        lastMetadata = metadata;
        return withRating(metadata);
    }

    /**
     * Hook A: called from the constructor of the like-status change event,
     * which YouTube creates on every like, dislike and undo.
     *
     * Both arguments are passed as Object so that no obfuscated type has to
     * be referenced. YouTube's generated enums override toString() to return
     * the bare ordinal, which is what makes this work.
     *
     * @param videoId the 11 character video id
     * @param azev    the new status as an "azev" enum
     */
    public static void onLikeStatusChanged(Object videoId, Object azev) {
        String id = videoId == null ? null : String.valueOf(videoId);
        if (id != null && id.length() > 0) {
            currentVideoId = id;
        }

        Integer value = parseEnum(azev);
        if (value == null) {
            return;
        }

        int status;
        switch (value) {
            case AZEV_LIKE:
                status = LIKE;
                break;
            case AZEV_DISLIKE:
                status = DISLIKE;
                break;
            case AZEV_INDIFFERENT:
                status = NONE;
                break;
            default:
                if (DEBUG) {
                    Log.w(TAG, "unknown azev value: " + value);
                }
                return;
        }

        // Bumped unconditionally: an interaction happened even when it did not
        // change the status being tracked, and the receiver has to see it.
        likeEventSeq++;
        setLikeStatus(status);
    }

    /**
     * Stores the status and republishes the metadata straight away, so the
     * change reaches MediaController without waiting for the next track.
     */
    public static void setLikeStatus(int status) {
        likeStatus = status;

        if (DEBUG) {
            Log.d(TAG, "likeStatus = " + status + " (" + currentTitle + ")");
        }

        MediaSession currentSession = session;
        MediaMetadata metadata = lastMetadata;
        if (currentSession == null || metadata == null) {
            return;
        }
        try {
            // This call originates in the extension rather than in YouTube's
            // own code, so it is not hooked and cannot recurse.
            currentSession.setMetadata(withRating(metadata));
        } catch (Exception ex) {
            Log.e(TAG, "republish failed", ex);
        }
    }

    public static int getLikeStatus() {
        return likeStatus;
    }

    public static String getCurrentVideoId() {
        return currentVideoId;
    }

    /**
     * Resolves the id of the video being played.
     *
     * ReVanced's videoInformationPatch already tracks this, and its extension
     * lives in the same APK and classloader, so it is reachable reflectively.
     * The proguard rules keep those names intact. Falls back to the id seen
     * on the last like interaction.
     */
    private static String resolveVideoId() {
        try {
            if (videoInformationMethod == null) {
                Class<?> videoInformation = Class.forName(
                        "app.revanced.extension.youtube.patches.VideoInformation");
                videoInformationMethod = videoInformation.getMethod("getVideoId");
            }
            Object id = videoInformationMethod.invoke(null);
            if (id != null && String.valueOf(id).length() > 0) {
                return String.valueOf(id);
            }
        } catch (Throwable ex) {
            // Logged once: if the class is missing it will stay missing.
            if (!videoInformationFailed) {
                videoInformationFailed = true;
                Log.w(TAG, "VideoInformation unavailable, falling back: " + ex);
            }
        }
        return currentVideoId;
    }

    /**
     * YouTube's generated enums override toString() to return the bare
     * ordinal, so they can be read without referencing obfuscated types.
     */
    private static Integer parseEnum(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(Integer.parseInt(String.valueOf(value)));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * Writes a long under an application specific key.
     *
     * MediaMetadata.Builder.putLong is annotated with the set of keys the
     * framework knows about, and custom ones are not part of it. At runtime the
     * builder only type-checks keys it recognises and passes the rest through -
     * YouTube publishes its own video dimension keys the same way. Routing the
     * key through a parameter stops the annotation check from firing on a
     * constant it cannot accept.
     */
    private static void putCustomLong(MediaMetadata.Builder builder, String key, long value) {
        builder.putLong(key, value);
    }

    private static MediaMetadata withRating(MediaMetadata metadata) {
        if (metadata == null) {
            return null;
        }
        try {
            MediaMetadata.Builder builder = new MediaMetadata.Builder(metadata)
                    .putRating(MediaMetadata.METADATA_KEY_USER_RATING,
                            likeStatus == LIKE
                                    ? Rating.newThumbRating(true)
                                    : Rating.newUnratedRating(Rating.RATING_THUMB_UP_DOWN));

            String videoId = resolveVideoId();
            if (videoId != null && videoId.length() > 0) {
                builder.putString(MediaMetadata.METADATA_KEY_MEDIA_ID, videoId);
            }

            putCustomLong(builder, KEY_LIKE_EVENT_SEQ, likeEventSeq);
            putCustomLong(builder, KEY_LIKE_STATUS, likeStatus);

            return builder.build();
        } catch (Exception ex) {
            Log.e(TAG, "withRating failed", ex);
            return metadata;
        }
    }
}