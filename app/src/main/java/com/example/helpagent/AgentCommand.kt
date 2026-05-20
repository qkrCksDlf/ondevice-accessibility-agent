package com.example.helpagent

data class AgentCommand(
    val intent: String,
    val stage: String = "",
    val systemAction: String = "",
    val query: String = "",
    val confirmationText: String = "",
    val autoMode: Boolean = false
)

val KOREA_MAJOR_STATIONS = listOf(
    "서울", "용산", "광명", "대전", "동대구",
    "대구", "울산", "부산", "광주송정", "여수엑스포"
)

data class KtxSlots(
    val departure: String? = null,
    val arrival: String? = null,
    val departMonth: String? = null,
    val departDay: Int? = null,
    val returnMonth: String? = null,
    val returnDay: Int? = null,
    val isOneWay: Boolean? = null
) {
    fun nextMissingSlot(): String? = when {
        departure == null -> "departure"
        arrival == null -> "arrival"
        departMonth == null || departDay == null -> "departDate"
        isOneWay == true -> null
        returnMonth == null || returnDay == null -> "returnDate"
        else -> null
    }

    fun merge(other: KtxSlots): KtxSlots = KtxSlots(
        departure = other.departure ?: departure,
        arrival = other.arrival ?: arrival,
        departMonth = other.departMonth ?: departMonth,
        departDay = other.departDay ?: departDay,
        returnMonth = other.returnMonth ?: returnMonth,
        returnDay = other.returnDay ?: returnDay,
        isOneWay = other.isOneWay ?: isOneWay
    )

    fun isComplete(): Boolean = nextMissingSlot() == null

    fun summary(): String {
        val tripType = if (isOneWay == true) "편도" else "왕복"
        val depart = "$departMonth ${departDay}일"
        return if (isOneWay == true) {
            "$departure → $arrival ($tripType, $depart)"
        } else {
            "$departure → $arrival ($tripType, $depart ~ $returnMonth ${returnDay}일)"
        }
    }
}

fun isValidStation(name: String?): Boolean {
    if (name.isNullOrBlank()) return false
    return KOREA_MAJOR_STATIONS.any { station ->
        name.contains(station) || station.contains(name)
    }
}

fun normalizeStation(name: String?): String? {
    if (name.isNullOrBlank()) return null
    return KOREA_MAJOR_STATIONS.firstOrNull { station ->
        name.contains(station) || station.contains(name)
    }
}