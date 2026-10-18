package asia.nana7mi.arirang.hook.config

import android.content.SharedPreferences
import asia.nana7mi.arirang.hook.xposed.HookBridge

/**
 * The manager app's `SharedPreferences` for [prefsName], served through libxposed.
 *
 * Works in any injected process without a Context and without the service bind, which is what
 * makes it the floor a config can always answer from — reads of the file itself are not an
 * option: the prefs that a hook needs are written `MODE_PRIVATE`, so another process cannot open
 * them, and the ones that are world-readable are already served here by the sync manager.
 */
internal fun xSharedPreferences(prefsName: String): SharedPreferences =
    HookBridge.getRemotePreferences(prefsName)
