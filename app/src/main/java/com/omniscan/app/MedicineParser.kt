package com.omniscan.app

import java.util.Calendar

enum class ExpiryStatus { EXPIRED, SOON, OK, UNKNOWN }

data class MedicineInfo(
    val known: List<Pair<String, String>>,
    val labelPurpose: List<String>,
    val composition: List<String>,
    val usage: List<String>,
    val warnings: List<String>,
    val expiryLabel: String?,
    val expiryStatus: ExpiryStatus,
    val checks: List<Pair<String, Boolean>>,
    val verdict: String
)

/**
 * Reads what is PRINTED on a medicine pack (via OCR text). It does not diagnose and
 * cannot prove a medicine is genuine.
 */
object MedicineParser {
    private val IC = RegexOption.IGNORE_CASE

    private val monthNames = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    private val hintRegexes: List<Regex> = listOf(
        "tablets?", "capsules?", "syrup", "suspension", "injection", "ointment", "drops",
        "dose", "dosage", "batch", "b\\.?\\s?no", "exp\\.?", "expiry", "mfg\\.?", "mfd\\.?",
        "i\\.?p\\.?", "b\\.?p\\.?", "usp", "schedule h", "composition", "each film", "strip",
        "keep out of reach", "pharma\\w*", "laboratories", "rx"
    ).map { Regex("\\b$it\\b", IC) }

    private val doseRegex = Regex("""\d+(\.\d+)?\s?(mg|mcg|µg|g|ml|iu)\b""", IC)

    private val expLabel = Regex(
        """\b(?:exp(?:iry|iration)?\.?(?:\s*date)?|use\s*before|best\s*before)[\s:.\-]*([^\n]{0,24}(?:\n[^\n]{0,24})?)""", IC
    )
    private val mfgLabel = Regex(
        """\b(?:mfg|mfd|manufactured|manufacturing)\.?(?:\s*date)?[\s:.\-]*([^\n]{0,24}(?:\n[^\n]{0,24})?)""", IC
    )
    private val batchLabel = Regex("""\b(?:b\.?\s?no|batch(?:\s*no)?|lot)\.?[\s:.\-]*([A-Z0-9][A-Z0-9\-/]{2,})""", IC)
    private val makerRegex = Regex(
        """(?:manufactured|mfd\.?|mfg\.?|marketed)\s*by|\b(?:pvt\.?\s*ltd|limited|ltd\.?|laboratories|pharmaceuticals?|pharma|inc\.?)\b""", IC
    )
    private val licenceRegex = Regex("""\bm\.?\s?l\.?\s?(?:no|lic)|mfg\.?\s*lic|lic(?:ence|ense)?\.?\s*no|drug\s*lic""", IC)

    private val usageRegex = Regex(
        """\b(dose|dosage|as directed|as prescribed|once|twice|thrice|daily|times a day|per day|after (?:food|meals?)|before (?:food|meals?)|with (?:food|water)|bedtime|every \d+\s*hours?|directions?)\b|\b\d-\d-\d\b""", IC
    )
    private val warnRegex = Regex(
        """\b(warning|caution|side effects?|do not|not to be|avoid|keep out|store|pregnan\w*|breast\w*|allerg\w*|drowsiness|prescription|schedule h)\b""", IC
    )
    private val purposeRegex = Regex(
        """\b(indicat\w*|used (?:for|in|to)|treatment of|relief of|for the relief|helps? (?:in|to|with))\b""", IC
    )

    /** Well-known generic/brand names -> plain-language purpose. General info only. */
    private val knownList: List<Pair<List<String>, String>> = listOf(
        listOf("paracetamol", "acetaminophen", "dolo", "crocin", "calpol") to "Reduces fever and relieves mild to moderate pain.",
        listOf("combiflam") to "Ibuprofen with paracetamol for pain and fever.",
        listOf("ibuprofen", "brufen") to "Relieves pain, fever and inflammation. Usually taken after food.",
        listOf("diclofenac", "voveran") to "Relieves pain and inflammation, for example joint or muscle pain.",
        listOf("aspirin", "disprin") to "Relieves pain and fever. Low doses are also used to reduce the risk of blood clots, only on a doctor's advice.",
        listOf("cetirizine", "levocetirizine") to "Relieves allergy symptoms such as sneezing, runny nose and itching. May cause drowsiness.",
        listOf("montelukast") to "Helps prevent asthma attacks and allergy symptoms.",
        listOf("salbutamol") to "Opens the airways to relieve wheezing and breathlessness.",
        listOf("amoxicillin", "amoxycillin") to "An antibiotic for bacterial infections. Finish the full course your doctor prescribed.",
        listOf("azithromycin") to "An antibiotic for bacterial infections.",
        listOf("ciprofloxacin") to "An antibiotic for bacterial infections.",
        listOf("metronidazole") to "Treats certain bacterial and parasitic infections.",
        listOf("metformin") to "Helps control blood sugar in type 2 diabetes.",
        listOf("amlodipine", "telmisartan", "losartan") to "Lowers high blood pressure.",
        listOf("atorvastatin", "rosuvastatin") to "Lowers cholesterol.",
        listOf("pantoprazole", "omeprazole", "esomeprazole") to "Reduces stomach acid. Used for acidity, reflux and ulcers.",
        listOf("domperidone", "ondansetron") to "Relieves nausea and vomiting.",
        listOf("loperamide") to "Treats diarrhoea.",
        listOf("levothyroxine") to "Replaces thyroid hormone when the thyroid is underactive.",
        listOf("cholecalciferol", "vitamin d3") to "Vitamin D supplement for bones and muscles.",
        listOf("folic acid") to "Vitamin supplement, often used in pregnancy and anaemia."
    )

    fun looksLikeMedicine(text: String): Boolean {
        var score = hintRegexes.count { it.containsMatchIn(text) }
        if (doseRegex.containsMatchIn(text)) score += 2
        return score >= 3
    }

    fun parse(text: String, hasCode: Boolean): MedicineInfo {
        val lines = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        val lower = text.lowercase()

        val known = ArrayList<Pair<String, String>>()
        for ((aliases, purpose) in knownList) {
            val hit = aliases.firstOrNull { Regex("\\b" + Regex.escape(it) + "\\b").containsMatchIn(lower) }
            if (hit != null) known.add(hit.replaceFirstChar { ch -> ch.uppercase() } to purpose)
            if (known.size >= 3) break
        }

        val exp = findDate(expLabel, text)
        val mfg = findDate(mfgLabel, text)
        var status = ExpiryStatus.UNKNOWN
        var expText: String? = null
        if (exp != null) {
            val (y, m) = exp
            expText = monthNames[m - 1].replaceFirstChar { ch -> ch.uppercase() } + " " + y
            val cal = Calendar.getInstance()
            val diff = (y - cal.get(Calendar.YEAR)) * 12 + (m - (cal.get(Calendar.MONTH) + 1))
            status = when {
                diff < 0 -> ExpiryStatus.EXPIRED
                diff <= 2 -> ExpiryStatus.SOON
                else -> ExpiryStatus.OK
            }
        }

        val hasBatch = batchLabel.findAll(text).map { it.groupValues[1] }.any { c -> c.any { ch -> ch.isDigit() } }
        val checks = listOf(
            "Batch number" to hasBatch,
            "Expiry date" to (exp != null),
            "Manufacturing date" to (mfg != null),
            "Manufacturer name" to makerRegex.containsMatchIn(text),
            "Licence number" to licenceRegex.containsMatchIn(text),
            "Barcode or QR code" to hasCode
        )
        val found = checks.count { it.second }
        val verdict = if (found >= 5) {
            "Most printed details were found. This does NOT prove the medicine is genuine. Buy only from licensed pharmacies."
        } else {
            "Some details are missing or unclear. Move closer and scan again. If the pack looks unusual, ask your pharmacist."
        }

        return MedicineInfo(
            known = known,
            labelPurpose = pick(lines, purposeRegex, 3),
            composition = pick(lines, doseRegex, 4),
            usage = pick(lines, usageRegex, 4),
            warnings = pick(lines, warnRegex, 4),
            expiryLabel = expText,
            expiryStatus = status,
            checks = checks,
            verdict = verdict
        )
    }

    fun summary(i: MedicineInfo): String {
        val sb = StringBuilder()
        fun section(title: String, items: List<String>) {
            if (items.isEmpty()) return
            sb.append(title).append('\n')
            items.forEach { sb.append(it).append('\n') }
            sb.append('\n')
        }
        section("USED FOR", i.known.map { it.first + ": " + it.second } + i.labelPurpose)
        section("CONTAINS", i.composition)
        val usage = i.usage.toMutableList()
        if (usage.isNotEmpty()) usage.add("Follow your doctor's or pharmacist's advice.")
        section("HOW TO USE", usage)
        section("WARNINGS", i.warnings)

        val found = i.checks.filter { it.second }.map { it.first }
        val missing = i.checks.filter { !it.second }.map { it.first }
        val check = mutableListOf<String>()
        if (found.isNotEmpty()) check.add("Found: " + found.joinToString(", ") + ".")
        if (missing.isNotEmpty()) check.add("Not found: " + missing.joinToString(", ") + ".")
        check.add(i.verdict)
        section("LABEL CHECK", check)

        sb.append("This only reads what is printed on the pack. It is not medical advice.")
        return sb.toString().trim()
    }

    private fun pick(lines: List<String>, regex: Regex, max: Int): List<String> =
        lines.filter { regex.containsMatchIn(it) }.map { it.take(90) }.distinct().take(max)

    private fun findDate(label: Regex, text: String): Pair<Int, Int>? {
        for (m in label.findAll(text)) {
            val d = parseMonthYear(m.groupValues[1])
            if (d != null) return d
        }
        return null
    }

    /** Returns (year, month) or null. Handles 08/2027, 8-27, 12/08/2027, AUG 2027, AUG-27. */
    fun parseMonthYear(s: String): Pair<Int, Int>? {
        val numeric = Regex("""(?:(\d{1,2})\s*[/\-.]\s*)?(\d{1,2})\s*[/\-.]\s*(\d{4}|\d{2})""").find(s)
        if (numeric != null) {
            val month = numeric.groupValues[2].toInt()
            var year = numeric.groupValues[3].toInt()
            if (year < 100) year += 2000
            if (month in 1..12 && year in 2000..2100) return Pair(year, month)
        }
        for (m in Regex("""([A-Za-z]{3,9})[\s.\-/']*(\d{4}|\d{2})""").findAll(s)) {
            val idx = monthNames.indexOf(m.groupValues[1].substring(0, 3).lowercase())
            if (idx >= 0) {
                var year = m.groupValues[2].toInt()
                if (year < 100) year += 2000
                if (year in 2000..2100) return Pair(year, idx + 1)
            }
        }
        return null
    }
}
