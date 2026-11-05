package com.omniscan.app

/** Spots common safety words on signs and returns a short alert (English). */
object HazardDetector {
    private val rules: List<Pair<Regex, String>> = listOf(
        "danger|dangerous" to "Danger notice",
        "caution|warning|beware" to "Caution notice",
        "wet floor|slippery" to "Slippery floor",
        "high voltage|electric shock|live wire" to "Electric hazard",
        "no entry|do not enter|keep out|restricted|authorised personnel|authorized personnel" to "Entry not allowed",
        "flammable|no smoking|fire hazard" to "Fire hazard",
        "poison|toxic|hazard|hazardous|chemical" to "Hazardous material",
        "under construction|road work|men at work|diversion" to "Work in progress",
        "stop" to "Stop notice"
    ).map { Regex("\\b(?:" + it.first + ")\\b", RegexOption.IGNORE_CASE) to it.second }

    fun check(text: String): String? {
        val hits = rules.filter { it.first.containsMatchIn(text) }.map { it.second }.distinct().take(2)
        return if (hits.isEmpty()) null else "Alert: " + hits.joinToString(", ")
    }
}
