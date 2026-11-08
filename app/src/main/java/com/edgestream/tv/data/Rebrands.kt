package com.edgestream.tv.data

/**
 * Providers keep channels under the names they had when the playlist was first built. This maps
 * retired and rebranded names to what the channel is called today, so the guide reads like a
 * current TV lineup. Each rule matches the whole (already cleaned) name, so "Dave" changes but
 * "Dave's Kitchen" doesn't; a trailing number or region in the pattern is carried across.
 *
 * Only the display name changes. Stored names, favourites and guide matching are untouched.
 */
object Rebrands {

    private fun rule(pattern: String, current: String) =
        Regex("^(?:$pattern)$", RegexOption.IGNORE_CASE) to current

    private val RULES = listOf(
        // ---- United States ----
        rule("MSNBC", "MS NOW"),                                            // Nov 2025
        rule("Bally Sports (.+)", "FanDuel Sports Network $1"),             // Oct 2024
        rule("Fox Sports (Arizona|Detroit|Florida|Midwest|North|Ohio|Oklahoma|San Diego|South|Southeast|Southwest|Sun|West|Wisconsin|Kansas City|New Orleans|Indiana)", "FanDuel Sports Network $1"),
        rule("NBC Sports Washington", "Monumental Sports Network"),         // 2023
        rule("Fox Sports 1", "FS1"),
        rule("Fox Sports 2", "FS2"),
        rule("Epix( \\d| Hits| Drive-In)?", "MGM+$1"),                        // 2023
        rule("Showtime", "Paramount+ with Showtime"),                       // 2024
        rule("Spike( TV)?", "Paramount Network"),                           // 2018
        rule("WGN America", "NewsNation"),                                  // 2021
        rule("ABC Family", "Freeform"),                                     // 2016
        rule("DIY( Network)?", "Magnolia Network"),                         // 2022
        rule("Velocity", "MotorTrend"),
        rule("Motor Trend", "MotorTrend"),
        rule("Viceland|H2", "Vice TV"),
        rule("Court TV Mystery|Escape( TV)?", "ION Mystery"),               // 2022
        rule("Ion Television|Ion TV", "ION"),
        rule("Ion (\\d+ .+)", "ION $1"),
        rule("Ion (Plus|Mystery)", "ION $1"),
        rule("Decades", "Catchy Comedy"),                                   // 2023
        rule("TBD( TV)?", "Roar"),                                          // 2023
        rule("Heroes (?:&|and) Icons", "H&I"),
        rule("Bounce", "Bounce TV"),
        rule("Me ?TV", "MeTV"),
        rule("Me ?TV ?\\+", "MeTV+"),
        rule("Me ?TV (Toons|Network)", "MeTV $1"),
        rule("Me ?TV (.+)", "MeTV $1"),
        rule("Hub Network|The Hub", "Discovery Family"),
        rule("Discovery Fit (?:&|and) Health", "Discovery Life"),
        rule("Military Channel", "American Heroes Channel"),
        rule("TV Guide Network|Pop", "Pop TV"),
        rule("Noggin", "Nick Jr."),
        rule("Sundance( Channel)?", "SundanceTV"),
        rule("National Geographic Channel|Nat Geo Channel", "National Geographic"),
        rule("History Channel|The History Channel", "History"),
        rule("Biography Channel", "FYI"),

        // ---- United Kingdom ----
        rule("BT Sport ([123])", "TNT Sports $1"),                          // July 2023
        rule("BT Sport ESPN", "TNT Sports 4"),
        rule("BT Sport Box Office", "TNT Sports Box Office"),
        rule("Sky One|Sky 1", "Sky Showcase"),                              // 2021
        rule("Dave", "U&Dave"),                                             // UKTV, 2024
        rule("Gold", "U&Gold"),
        rule("Alibi", "U&Alibi"),
        rule("Drama", "U&Drama"),
        rule("W", "U&W"),
        rule("Yesterday", "U&Yesterday"),
        rule("Eden", "U&Eden"),

        // ---- Australia ----
        rule("One|One HD|10 One", "10 Bold"),                               // 2018
        rule("Eleven|11", "10 Peach"),                                      // 2018
        rule("Network Ten|Ten|Channel Ten", "10"),
        rule("SBS ?2", "SBS Viceland"),
        rule("ABC ?2|ABC Comedy|ABC TV Plus", "ABC Family"),                // 2024
        rule("ABC News 24", "ABC News"),

        // ---- Canada ----
        rule("The Comedy Network|Comedy Network", "CTV Comedy Channel"),    // 2019
        rule("Gusto( TV)?", "CTV Life Channel"),
        rule("Space Channel", "CTV Sci-Fi Channel"),
    )

    /** Today's name for [name], or [name] unchanged. */
    fun current(name: String): String {
        val n = name.trim()
        for ((re, replacement) in RULES) {
            val m = re.matchEntire(n) ?: continue
            return m.groupValues.drop(1).foldIndexed(replacement) { i, acc, g -> acc.replace("$${i + 1}", g) }
                .replace(Regex("\\s+"), " ").trim()
        }
        return n
    }
}
