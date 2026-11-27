package com.antenna.data

import android.content.Context
import androidx.core.content.edit
import com.antenna.sponsorblock.SponsorCategory

class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("antenna", Context.MODE_PRIVATE)

    /** SponsorBlock categories to auto-skip (API keys like "sponsor", "selfpromo"). */
    var skipCategories: Set<String>
        get() = sp.getStringSet(KEY_CATEGORIES, null)?.toSet() ?: SponsorCategory.DEFAULT_ON
        set(value) = sp.edit { putStringSet(KEY_CATEGORIES, value) }

    /** Highest video resolution to pick (e.g. 1080). */
    var maxHeight: Int
        get() = sp.getInt(KEY_MAX_HEIGHT, 1080)
        set(value) = sp.edit { putInt(KEY_MAX_HEIGHT, value) }

    /**
     * Highest video resolution on mobile data and other metered networks, or [SAME_AS_WIFI].
     * Video is nearly all the data the app uses, and 480p is several times less than 1080p.
     */
    var maxHeightMetered: Int
        get() = sp.getInt(KEY_MAX_HEIGHT_METERED, 480)
        set(value) = sp.edit { putInt(KEY_MAX_HEIGHT_METERED, value) }

    /**
     * Tell SponsorBlock when a segment is skipped (its public "time saved" stats). Off by
     * default: the report names the segment, which tells SponsorBlock the exact video,
     * unlike the anonymous lookup.
     */
    var reportSkips: Boolean
        get() = sp.getBoolean(KEY_REPORT_SKIPS, false)
        set(value) = sp.edit { putBoolean(KEY_REPORT_SKIPS, value) }

    /** Hide videos you've finished from the Subscriptions feed. */
    var hideWatched: Boolean
        get() = sp.getBoolean(KEY_HIDE_WATCHED, false)
        set(value) = sp.edit { putBoolean(KEY_HIDE_WATCHED, value) }

    /**
     * When the app opens, fetch the details of a few videos you're likely to open (Prefetcher),
     * so they start sooner. On by default: it's a few small requests, and it stops fetching
     * by itself when its guesses keep missing.
     */
    var prefetch: Boolean
        get() = sp.getBoolean(KEY_PREFETCH, true)
        set(value) = sp.edit { putBoolean(KEY_PREFETCH, value) }

    /** Where the floating mini player was left (big screens), and how big. */
    var miniPlayer: MiniPlayerPlace
        get() = MiniPlayerPlace(sp.getFloat(KEY_MINI_X, 1f), sp.getFloat(KEY_MINI_Y, 1f), sp.getFloat(KEY_MINI_WIDTH, 0f))
        set(value) = sp.edit {
            putFloat(KEY_MINI_X, value.x)
            putFloat(KEY_MINI_Y, value.y)
            putFloat(KEY_MINI_WIDTH, value.widthDp)
        }

    companion object {
        /** [maxHeightMetered]: no lower limit on mobile data. */
        const val SAME_AS_WIFI = 0

        private const val KEY_MAX_HEIGHT_METERED = "max_height_metered"
        private const val KEY_HIDE_WATCHED = "hide_watched"
        private const val KEY_PREFETCH = "prefetch"
        private const val KEY_CATEGORIES = "sb_categories"
        private const val KEY_MAX_HEIGHT = "max_height"
        private const val KEY_REPORT_SKIPS = "sb_report_skips"
        private const val KEY_MINI_X = "mini_player_x"
        private const val KEY_MINI_Y = "mini_player_y"
        private const val KEY_MINI_WIDTH = "mini_player_width"
    }
}

/**
 * Where the floating mini player is, as fractions of the room it can move in ([x], [y]: 0 at
 * the left or top, 1 at the right or bottom, so it stays by the same edges when the window
 * changes), and its width in dp: 0 until it's resized, for its usual size.
 */
data class MiniPlayerPlace(val x: Float = 1f, val y: Float = 1f, val widthDp: Float = 0f)
