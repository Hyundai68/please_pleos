package com.pleose.shared.text

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** 한국어 조사·시간 표현 */
object Korean {

    /** 마지막 글자에 받침이 있는지. 한글이 아니면 숫자·영문 발음으로 대략 판단 */
    fun hasBatchim(word: String): Boolean {
        val c = word.trim().lastOrNull { !it.isWhitespace() && it != ')' && it != '!' && it != '.' } ?: return false
        if (c in '가'..'힣') return (c - '가') % 28 != 0
        // 숫자 읽기: 0(영) 1(일) 3(삼) 6(육) 7(칠) 8(팔) 은 받침 있음
        if (c.isDigit()) return c in "013678"
        return c.lowercaseChar() in "lmnr"
    }

    /** 받침이 ㄹ인지 (으로/로 구분용) */
    private fun hasRieulBatchim(word: String): Boolean {
        val c = word.trim().lastOrNull() ?: return false
        return c in '가'..'힣' && (c - '가') % 28 == 8
    }

    /** 호칭: "수진" → "수진님", "어머님" → "어머님" */
    fun nim(name: String) = if (name.endsWith("님")) name else "${name}님"

    /** 을/를 */
    fun eul(word: String) = word + if (hasBatchim(word)) "을" else "를"

    /** 이/가 */
    fun i(word: String) = word + if (hasBatchim(word)) "이" else "가"

    /** 은/는 */
    fun eun(word: String) = word + if (hasBatchim(word)) "은" else "는"

    /** 으로/로 (받침 없거나 ㄹ받침이면 '로') */
    fun euro(word: String) = word + if (hasBatchim(word) && !hasRieulBatchim(word)) "으로" else "로"

    /** 에서 (받침 무관) */
    fun eseo(word: String) = "${word}에서"

    /** 와/과 */
    fun wa(word: String) = word + if (hasBatchim(word)) "과" else "와"

    /** 이에요/예요 */
    fun ieyo(word: String) = word + if (hasBatchim(word)) "이에요" else "예요"

    /** 개수 세기: 1 → "한", 2 → "두" … 10 넘으면 숫자 */
    fun count(n: Int): String = listOf("한", "두", "세", "네", "다섯", "여섯", "일곱", "여덟", "아홉", "열").getOrNull(n - 1) ?: "$n"

    /** "A와 B", "A, B와 C" */
    fun and(words: List<String>): String = when (words.size) {
        0 -> ""
        1 -> words[0]
        else -> words.dropLast(1).joinToString(", ").let { wa(it) } + " " + words.last()
    }
}

/** 시간 표시: 기기 시간대를 따른다 (한국 차량·휴대폰은 한국 표준시). Pleos 내비 화면의 시각과 맞춘다 */
object Kst {
    val zone: ZoneId get() = ZoneId.systemDefault()
    private val hhmm = DateTimeFormatter.ofPattern("HH:mm")

    fun at(epochMs: Long): ZonedDateTime = Instant.ofEpochMilli(epochMs).atZone(zone)

    /** "18:52" */
    fun hhmm(epochMs: Long): String = at(epochMs).format(hhmm)

    fun minuteOfDay(epochMs: Long): Int = at(epochMs).let { it.hour * 60 + it.minute }

    /** 음성용: "5시", "5시 2분", "9시 반" 대신 "9시 30분" (12시간제, 오전/오후 생략) */
    fun spoken(epochMs: Long): String {
        val t = at(epochMs)
        val h = (t.hour % 12).let { if (it == 0) 12 else it }
        return if (t.minute == 0) "${h}시" else "${h}시 ${t.minute}분"
    }

    /** 음성용 24시간제: "18시 50분", "18시" — 약속·도착 예정을 말할 때 (기획안 음성 예시) */
    fun spokenHm(epochMs: Long): String {
        val t = at(epochMs)
        return if (t.minute == 0) "${t.hour}시" else "${t.hour}시 ${t.minute}분"
    }

    /** 오늘 [minuteOfDay]의 시각 (즐겨찾기의 약속 시각) */
    fun todayAt(minuteOfDay: Int, now: Long = System.currentTimeMillis()): Long =
        at(now).toLocalDate().atStartOfDay(zone).plusMinutes(minuteOfDay.toLong()).toInstant().toEpochMilli()

    /** 1=월 … 7=일 */
    fun dayOfWeek(epochMs: Long): Int = at(epochMs).dayOfWeek.value

    /** 음성용 마감 시각: OpeningHours.closeMin → "9시" */
    fun spokenMinuteOfDay(minute: Int): String {
        val m = minute % (24 * 60)
        val h = (m / 60 % 12).let { if (it == 0) 12 else it }
        return if (m % 60 == 0) "${h}시" else "${h}시 ${m % 60}분"
    }

    /** 목록용: 오늘이면 "18:36", 어제면 "어제 19:12", 그 전이면 "11/2 19:12" */
    fun relativeDay(epochMs: Long, now: Long): String {
        val t = at(epochMs)
        val today = at(now).toLocalDate()
        return when (t.toLocalDate()) {
            today -> hhmm(epochMs)
            today.minusDays(1) -> "어제 ${hhmm(epochMs)}"
            else -> "${t.monthValue}/${t.dayOfMonth} ${hhmm(epochMs)}"
        }
    }

    /** "방금 전", "3분 전" */
    fun ago(epochMs: Long, now: Long): String {
        val min = ((now - epochMs) / 60_000).coerceAtLeast(0)
        return when {
            min < 1 -> "방금 전"
            min < 60 -> "${min}분 전"
            else -> hhmm(epochMs)
        }
    }
}

/** 거리 표시: "300m" / "1.2km" */
fun formatDistance(m: Double): String = when {
    m >= 1000 -> "%.1fkm".format(m / 1000)
    m >= 100 -> "${(m / 100).toInt() * 100}m"
    else -> "${(m / 10).toInt().coerceAtLeast(1) * 10}m"
}

/** 남은 시간 표시: "3분" / "1시간 5분" */
fun formatMinutes(sec: Int): String {
    val min = ((sec + 59) / 60).coerceAtLeast(1)
    return if (min < 60) "${min}분" else "${min / 60}시간${if (min % 60 > 0) " ${min % 60}분" else ""}"
}
