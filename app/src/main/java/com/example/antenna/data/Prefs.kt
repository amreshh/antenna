package com.example.antenna.data

import android.content.Context
import androidx.core.content.edit
import com.example.antenna.sponsorblock.SponsorCategory

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

    companion object {
        /** [maxHeightMetered]: no lower limit on mobile data. */
        const val SAME_AS_WIFI = 0

        private const val KEY_MAX_HEIGHT_METERED = "max_height_metered"
        private const val KEY_HIDE_WATCHED = "hide_watched"
        private const val KEY_CATEGORIES = "sb_categories"
        private const val KEY_MAX_HEIGHT = "max_height"
        private const val KEY_REPORT_SKIPS = "sb_report_skips"
    }
}
