package com.example.vehiclechecker

import androidx.annotation.Keep
import java.util.Locale

/**
 * Evaluates historical MOT test records (failures & advisories) to score 5 key vehicle
 * component systems from 0 to 100% and assign risk ratings (Green, Amber, Red).
 *
 * Designed to be pure Kotlin with no Android framework dependencies so it can be unit tested on the JVM.
 */
object ComponentHealthCalculator {

    @Keep
    enum class ComponentDomain(val displayName: String) {
        BRAKES("Brakes"),
        SUSPENSION_STEERING("Suspension & Steering"),
        TYRES_WHEELS("Tyres & Wheels"),
        EXHAUST_EMISSIONS("Exhaust & Emissions"),
        STRUCTURE_ELECTRICS("Structure & Electrics")
    }

    @Keep
    enum class ComponentStatus {
        GREEN,  // Good Condition (80 - 100%)
        AMBER,  // Moderate Wear / Watchlist (50 - 79%)
        RED     // High Risk / Urgent Attention (0 - 49%)
    }

    @Keep
    data class ComponentHealth(
        val domain: ComponentDomain,
        val score: Int, // 0 to 100
        val status: ComponentStatus,
        val advisoryCount: Int,
        val failureCount: Int,
        val latestNote: String
    )

    @Keep
    data class HealthReport(
        val overallScore: Int, // 0 to 100
        val overallStatus: ComponentStatus,
        val components: List<ComponentHealth>
    )

    fun calculate(mot: MotHistoryData): HealthReport {
        if (mot.tests.isEmpty()) {
            val defaultComponents = ComponentDomain.entries.map { domain ->
                ComponentHealth(
                    domain = domain,
                    score = 100,
                    status = ComponentStatus.GREEN,
                    advisoryCount = 0,
                    failureCount = 0,
                    latestNote = "No recorded MOT history."
                )
            }
            return HealthReport(100, ComponentStatus.GREEN, defaultComponents)
        }

        val domainScores = mutableMapOf<ComponentDomain, DomainAccumulator>()
        ComponentDomain.entries.forEach { domain ->
            domainScores[domain] = DomainAccumulator()
        }

        // Walk tests newest-first (mot.tests is ordered newest first)
        mot.tests.forEachIndexed { index, test ->
            // Recency multiplier: 1.0 for latest test, decaying for older tests
            val recencyMultiplier = when (index) {
                0 -> 1.0
                1 -> 0.75
                2 -> 0.5
                3 -> 0.3
                else -> 0.15
            }

            test.failures.forEach { text ->
                val domain = categorize(text)
                if (domain != null) {
                    val acc = domainScores.getValue(domain)
                    acc.failureCount++
                    acc.penalties += 25.0 * recencyMultiplier
                    if (acc.latestNote == null) {
                        acc.latestNote = "Defect (${test.dateTested}): ${text.trim()}"
                    }
                }
            }

            test.advisories.forEach { text ->
                val domain = categorize(text)
                if (domain != null) {
                    val acc = domainScores.getValue(domain)
                    acc.advisoryCount++
                    acc.penalties += 10.0 * recencyMultiplier
                    if (acc.latestNote == null) {
                        acc.latestNote = "Advisory (${test.dateTested}): ${text.trim()}"
                    }
                }
            }
        }

        // Apply recurring issue extra penalties
        mot.recurringIssues.forEach { issue ->
            val domain = categorize(issue.text)
            if (domain != null) {
                val acc = domainScores.getValue(domain)
                val extraPenalty = if (issue.isFailure) 15.0 else 8.0
                acc.penalties += extraPenalty * issue.occurrences
            }
        }

        val componentHealthList = ComponentDomain.entries.map { domain ->
            val acc = domainScores.getValue(domain)
            val finalScore = (100.0 - acc.penalties).coerceIn(0.0, 100.0).toInt()
            val status = when {
                finalScore >= 80 -> ComponentStatus.GREEN
                finalScore >= 50 -> ComponentStatus.AMBER
                else -> ComponentStatus.RED
            }
            ComponentHealth(
                domain = domain,
                score = finalScore,
                status = status,
                advisoryCount = acc.advisoryCount,
                failureCount = acc.failureCount,
                latestNote = acc.latestNote ?: "No issues recorded across MOT history."
            )
        }

        val avgScore = componentHealthList.map { it.score }.average().toInt()
        val lowestScore = componentHealthList.minOf { it.score }
        // Overall score is heavily influenced by the weakest system
        val overallScore = ((avgScore * 0.6) + (lowestScore * 0.4)).toInt().coerceIn(0, 100)

        val overallStatus = when {
            overallScore >= 80 -> ComponentStatus.GREEN
            overallScore >= 50 -> ComponentStatus.AMBER
            else -> ComponentStatus.RED
        }

        return HealthReport(overallScore, overallStatus, componentHealthList)
    }

    fun categorize(text: String): ComponentDomain? {
        val lower = text.lowercase(Locale.UK)

        // Brakes
        if (lower.contains("brake") || lower.contains("pad") || lower.contains("disc") ||
            lower.contains("caliper") || lower.contains("handbrake") || lower.contains("lining") ||
            lower.contains("abs") || lower.contains("frictional")
        ) {
            return ComponentDomain.BRAKES
        }

        // Tyres & Wheels
        if (lower.contains("tyre") || lower.contains("tire") || lower.contains("wheel") ||
            lower.contains("tread") || lower.contains("rim") || lower.contains("bearing") ||
            lower.contains("valve") || lower.contains("alignment")
        ) {
            return ComponentDomain.TYRES_WHEELS
        }

        // Suspension & Steering
        if (lower.contains("suspension") || lower.contains("spring") || lower.contains("shock") ||
            lower.contains("damper") || lower.contains("bush") || lower.contains("ball joint") ||
            lower.contains("arm") || lower.contains("anti-roll") || lower.contains("steering") ||
            lower.contains("track rod") || lower.contains("rack") || lower.contains("power steering") ||
            lower.contains("sway") || lower.contains("strut")
        ) {
            return ComponentDomain.SUSPENSION_STEERING
        }

        // Exhaust & Emissions
        if (lower.contains("exhaust") || lower.contains("catalyt") || lower.contains("dpf") ||
            lower.contains("particulate") || lower.contains("emissions") || lower.contains("lambda") ||
            lower.contains("silencer") || lower.contains("leak") || lower.contains("smoke") ||
            lower.contains("gaseous") || lower.contains("co2")
        ) {
            return ComponentDomain.EXHAUST_EMISSIONS
        }

        // Structure, Body & Electrics
        if (lower.contains("corrosion") || lower.contains("rust") || lower.contains("sill") ||
            lower.contains("subframe") || lower.contains("chassis") || lower.contains("underbody") ||
            lower.contains("seatbelt") || lower.contains("lamp") || lower.contains("light") ||
            lower.contains("reflector") || lower.contains("wiper") || lower.contains("horn") ||
            lower.contains("windscreen") || lower.contains("mirror") || lower.contains("door") ||
            lower.contains("structure") || lower.contains("frame")
        ) {
            return ComponentDomain.STRUCTURE_ELECTRICS
        }

        return null
    }

    private class DomainAccumulator {
        var penalties = 0.0
        var advisoryCount = 0
        var failureCount = 0
        var latestNote: String? = null
    }
}
