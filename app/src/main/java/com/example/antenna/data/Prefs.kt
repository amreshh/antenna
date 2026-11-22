package com.example.antenna.data

import android.content.Context
import com.example.antenna.sponsorblock.SponsorCategory

class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("antenna", Context.MODE_PRIVATE)

    /** SponsorBlock categories to auto-skip (API keys like "sponsor", "selfpromo"). */
    var skipCategories: Set<String>
        get() = sp.getStringSet(KEY_CATEGORIES, null)?.toSet() ?: SponsorCategory.DEFAULT_ON
        set(value) = sp.edit().putStringSet(KEY_CATEGORIES, value).apply()

    /** Highest video resolution to pick (e.g. 1080). */
    var maxHeight: Int
        get() = sp.getInt(KEY_MAX_HEIGHT, 1080)
        set(value) = sp.edit().putInt(KEY_MAX_HEIGHT, value).apply()

    /** Hide videos you've finished from the Subscriptions feed. */
    var hideWatched: Boolean
        get() = sp.getBoolean(KEY_HIDE_WATCHED, false)
        set(value) = sp.edit().putBoolean(KEY_HIDE_WATCHED, value).apply()

    private companion object {
        const val KEY_HIDE_WATCHED = "hide_watched"
        const val KEY_CATEGORIES = "sb_categories"
        const val KEY_MAX_HEIGHT = "max_height"
    }
}
