package com.antenna.data

/**
 * Guesses what each subscribed channel is about, for the Subscriptions tab's "Topic" order,
 * from what's already on the phone (no requests):
 *  - YouTube's category of the videos you watched from it (saved with your history), which
 *    counts most;
 *  - its name ("… Tech Tips", "… News") and the titles of its recent videos (the feed), by
 *    keywords for each topic.
 * Topics are YouTube's category names. A channel nothing points at is [OTHER].
 */
object ChannelTopics {

    const val OTHER = "Other"

    /** A watched video's category counts as this many title matches. */
    private const val CATEGORY_WEIGHT = 3

    /** The channel's name, as this many title matches: it rarely changes with the latest video. */
    private const val NAME_WEIGHT = 2

    /**
     * Title words and phrases (lowercase) that point at a topic. Distinctive ones only: a
     * word like "live" or "review" fits too many topics to tell anything.
     */
    private val KEYWORDS: Map<String, List<String>> = mapOf(
        "News & Politics" to listOf(
            "news", "politics", "political", "trump", "biden", "harris", "kamala", "vance", "obama", "election",
            "elections", "voters", "voting", "senate", "senator", "congress", "democrats", "democrat", "republicans",
            "republican", "gop", "maga", "president", "presidential", "government", "supreme court", "white house",
            "ukraine", "russia", "putin", "zelensky", "israel", "gaza", "hamas", "iran", "nato", "tariff", "tariffs",
            "impeachment", "scandal", "fox news", "cnn", "msnbc", "liberal", "conservative", "governor", "mayor",
            "pelosi", "schumer", "sanders", "newsom", "desantis", "epstein", "parliament", "prime minister",
        ),
        "Science & Technology" to listOf(
            "linux", "windows", "ubuntu", "android", "iphone", "ipad", "macbook", "apple", "samsung", "pixel",
            "nvidia", "amd", "intel", "cpu", "gpu", "rtx", "radeon", "ryzen", "geforce", "motherboard", "ssd", "pc",
            "laptop", "laptops", "tech", "technology", "software", "hardware", "ai", "chatgpt", "openai", "robot",
            "robots", "programming", "coding", "python", "javascript", "kernel", "server", "homelab", "router",
            "3d printing", "3d printer", "smartphone", "gadget", "gadgets", "keyboard", "chip", "chips",
            "semiconductor", "quantum", "physics", "chemistry", "science", "scientists", "space", "nasa", "spacex",
            "rocket", "starship", "telescope", "astronomy", "planet", "planets", "galaxy", "black hole", "universe",
            "asteroid", "mars", "tesla", "engineering", "nuclear", "fusion",
        ),
        "Gaming" to listOf(
            "game", "games", "gaming", "gameplay", "gamer", "gta", "minecraft", "fortnite", "roblox", "xbox",
            "playstation", "ps5", "ps4", "nintendo", "steam", "elden ring", "zelda", "pokemon", "mario", "speedrun",
            "walkthrough", "let's play", "esports", "call of duty", "warzone", "valorant", "league of legends",
            "skyrim", "fallout", "cyberpunk", "halo", "witcher", "baldur's gate", "diablo", "starfield", "dlc", "rpg",
        ),
        "Music" to listOf(
            "music", "song", "songs", "album", "official music video", "official audio", "lyrics", "lyric video",
            "remix", "concert", "acoustic", "mixtape", "playlist", "dj", "band", "rap", "rapper", "hip hop", "metal",
            "jazz", "classical", "piano", "guitar", "drums", "singer", "megamix", "greatest hits", "hits",
        ),
        "Education" to listOf(
            "explained", "history", "historical", "lesson", "lessons", "lecture", "course", "learn", "learning",
            "documentary", "facts", "philosophy", "math", "maths", "mathematics", "grammar", "ancient", "empire",
            "civilization", "medieval", "ww2", "wwii", "world war", "geography", "psychology",
        ),
        "Sports" to listOf(
            "football", "soccer", "nfl", "nba", "basketball", "baseball", "mlb", "tennis", "golf", "hockey", "nhl",
            "f1", "formula 1", "wrestling", "wwe", "aew", "ufc", "mma", "boxing", "premier league",
            "champions league", "olympics", "cricket", "rugby", "touchdown", "playoffs", "super bowl", "world cup",
        ),
        "Comedy" to listOf("comedy", "funny", "stand-up", "standup", "sketch", "parody", "skit", "jokes", "comedian", "roast", "satire"),
        "Entertainment" to listOf(
            "movie", "movies", "trailer", "netflix", "hollywood", "celebrity", "marvel", "star wars", "reality tv",
            "reaction", "reacts", "challenge", "prank",
        ),
        "Howto & Style" to listOf(
            "how to", "diy", "recipe", "recipes", "cooking", "baking", "kitchen", "makeup", "fashion", "skincare",
            "outfit", "garden", "gardening", "farm", "farming", "homestead", "woodworking", "repair", "restoration",
            "cleaning",
        ),
        "Autos & Vehicles" to listOf(
            "car", "cars", "truck", "trucks", "engine", "motorcycle", "porsche", "bmw", "ferrari", "toyota",
            "supercar", "dashcam", "mechanic",
        ),
        "Pets & Animals" to listOf("dog", "dogs", "cat", "cats", "puppy", "kitten", "pets", "animals", "wildlife", "birds", "horse"),
        "Travel & Events" to listOf("travel", "trip", "vacation", "flight", "airport", "hotel", "cruise", "backpacking", "road trip"),
        "Film & Animation" to listOf("animation", "animated", "cartoon", "anime", "short film", "pixar", "stop motion"),
        "People & Blogs" to listOf("vlog", "vlogs", "day in the life", "storytime", "q&a"),
    )

    /** The topic of each channel, by url. */
    fun of(channels: List<Channel>, feed: List<VideoItem>, history: Collection<WatchEntry>): Map<String, String> {
        val titles = feed.groupBy { it.channelUrl }
        val categories = history.filter { it.category != null && it.video.channelUrl != null }
            .groupBy({ it.video.channelUrl }, { it.category!! })
        return channels.associate { channel ->
            channel.url to topicOf(titles[channel.url].orEmpty().map { it.title }, categories[channel.url].orEmpty(), channel.name)
        }
    }

    /**
     * The topic for a channel called [name], with these recent [titles] and watched videos'
     * [categories]: the one most of them point at, at least two (or one, for a channel with
     * only a video or two to go on), else [OTHER]. Ties go to the order of [KEYWORDS].
     */
    internal fun topicOf(titles: List<String>, categories: List<String>, name: String = ""): String {
        val votes = LinkedHashMap<String, Int>()
        KEYWORDS.keys.forEach { votes[it] = 0 }
        categories.forEach { votes.merge(it, CATEGORY_WEIGHT, Int::plus) }
        fun vote(text: String, weight: Int) {
            val words = text.lowercase().split(NOT_WORD).map { it.removeSuffix("'s") }.filter { it.isNotEmpty() }
            val padded = " " + words.joinToString(" ") + " "
            KEYWORDS.forEach { (topic, keywords) ->
                if (keywords.any { padded.contains(" $it ") }) votes.merge(topic, weight, Int::plus)
            }
        }
        if (name.isNotBlank()) vote(name, NAME_WEIGHT)
        titles.forEach { vote(it, 1) }
        val needed = if (titles.size + categories.size <= 2) 1 else 2
        val best = votes.maxByOrNull { it.value } ?: return OTHER
        return if (best.value >= needed) best.key else OTHER
    }

    /** Splits titles into words, keeping the apostrophes, &, ' and - that the keywords have. */
    private val NOT_WORD = Regex("""[^\p{L}\p{N}'&+-]+""")
}
