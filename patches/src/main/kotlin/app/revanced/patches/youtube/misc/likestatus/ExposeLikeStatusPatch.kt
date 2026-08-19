package app.revanced.patches.youtube.misc.likestatus

import app.revanced.patcher.*
import app.revanced.patcher.extensions.*
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS =
    "Lapp/revanced/extension/youtube/LikeStatusHook;"

/**
 * Hook B target. A framework class, so these names are never obfuscated and
 * this hook survives YouTube updates. The call itself sits inside the bundled
 * copy of the media support library, which YouTube uses to drive its session.
 */
private const val SESSION_CLASS = "Landroid/media/session/MediaSession;"
private const val SESSION_METHOD = "setMetadata"

/**
 * Hook A target: the event object YouTube constructs on every like, dislike
 * and undo. Its constructor signature is (String videoId, azev status, boolean).
 *
 * This name IS obfuscated and will change with YouTube updates. jadx shows the
 * class under "package p000", but in the dex it lives in the default package,
 * so the descriptor carries no package prefix. To find it again, search the
 * decompiled APK for the string INDIFFERENT and follow the azev enum to the
 * event class constructed in the like handling path.
 */
private const val LIKE_EVENT_CLASS = "Liyl;"

@Suppress("unused")
val exposeLikeStatusPatch = bytecodePatch(
    name = "Expose like status in MediaSession",
    description = "Publishes the video like status and id in MediaMetadata, " +
        "so external apps can read them through a MediaController.",
) {
    compatibleWith("com.google.android.youtube")

    extendWith("extensions/extension.rve")

    apply {
        // ---------------------------------------------------------------
        // Hook B: route the metadata through the extension on its way to
        // the media session, adding the rating and the video id.
        // ---------------------------------------------------------------
        var metadataHooks = 0

        classDefs.flatMap { classDef ->
            classDef.methods.mapNotNull { method ->
                val indices = method.instructionsOrNull?.mapIndexedNotNull { index, instruction ->
                    if (instruction.opcode != Opcode.INVOKE_VIRTUAL) return@mapIndexedNotNull null

                    val reference =
                        (instruction as? ReferenceInstruction)?.reference as? MethodReference
                            ?: return@mapIndexedNotNull null

                    if (reference.definingClass == SESSION_CLASS &&
                        reference.name == SESSION_METHOD
                    ) index else null
                } ?: return@mapNotNull null

                if (indices.any()) method to indices.toList() else null
            }
        }.forEach { (immutableMethod, indices) ->
            val method = firstMethod(immutableMethod)

            // Iterate backwards: every insertion shifts the following indices.
            indices.asReversed().forEach { index ->
                val instruction = method.getInstruction<FiveRegisterInstruction>(index)

                // invoke-virtual {vC, vD}: vC is the MediaSession, vD the metadata.
                val sessionRegister = instruction.registerC
                val metadataRegister = instruction.registerD

                method.addInstructions(
                    index,
                    """
                        invoke-static { v$sessionRegister, v$metadataRegister }, $EXTENSION_CLASS->addRating(Landroid/media/session/MediaSession;Landroid/media/MediaMetadata;)Landroid/media/MediaMetadata;
                        move-result-object v$metadataRegister
                    """,
                )
                metadataHooks++
            }
        }

        println("Expose like status - metadata hooks: $metadataHooks")
        if (metadataHooks == 0) {
            throw PatchException("No calls to $SESSION_CLASS->$SESSION_METHOD found")
        }

        // ---------------------------------------------------------------
        // Hook A: observe explicit like status changes made by the user.
        // ---------------------------------------------------------------
        var likeHooks = 0

        classDefs.filter { it.type == LIKE_EVENT_CLASS }.forEach { classDef ->
            classDef.methods.filter { it.name == "<init>" }.forEach { immutableMethod ->
                val method = firstMethod(immutableMethod)

                // p1 = videoId, p2 = the azev status enum.
                method.addInstructions(
                    0,
                    "invoke-static { p1, p2 }, $EXTENSION_CLASS->onLikeStatusChanged(Ljava/lang/Object;Ljava/lang/Object;)V",
                )
                likeHooks++
            }
        }

        println("Expose like status - like event hooks: $likeHooks")
        if (likeHooks == 0) {
            // Help the next person find the renamed class.
            classDefs.filter { it.type.contains("iyl") }
                .forEach { println("  candidate: ${it.type}") }
            throw PatchException(
                "Constructor of $LIKE_EVENT_CLASS not found. The class was likely " +
                    "renamed by a YouTube update - search the APK for INDIFFERENT."
            )
        }
    }
}