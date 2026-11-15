package com.pleose.shared.interpret

import com.pleose.shared.model.ErrandType
import com.pleose.shared.model.PlaceCategory
import com.pleose.shared.text.Korean

/** 부탁 해석 결과 (F-06) */
data class Interpretation(
    /** 해석된 유형 (요청자가 고른 유형이 있으면 그 값) */
    val type: ErrandType,
    /** 요청자가 유형을 고르지 않아 문장에서 자동 분류했는지 */
    val autoClassified: Boolean,
    /** "해열제(시럽형)" */
    val item: String,
    /** "아이 해열제 (시럽형)" */
    val title: String,
    /** 사 와줘: 들를 장소 종류 */
    val category: PlaceCategory?,
    /** 데려와줘: 데려올 사람 */
    val person: String?,
    /** 품목을 알아듣지 못함 → 요청자에게 품목 확인 요청 */
    val ambiguous: Boolean,
    /** 요청자 화면 안내 ("약국으로 찾아볼게요 · 해열제(시럽형)") */
    val hint: String,
)

/**
 * 부탁 문장 해석: ① 품목·행동 추출 → ② 장소 종류 매칭 (해열제 → 약국, 우유 → 편의점).
 *
 * MVP 외부 연동의 '언어 모델' 자리에 들어갈 규칙 기반 해석기다. 오프라인에서도 같은 결과를 내므로
 * 요청자 앱의 실시간 미리보기와 서버 해석에 함께 쓴다. 언어 모델을 붙이면 이 결과를 1차 후보로 쓰면 된다.
 */
object ErrandInterpreter {

    private data class Keyword(val word: String, val item: String, val category: PlaceCategory)

    private val buyKeywords: List<Keyword> = buildList {
        fun add(cat: PlaceCategory, vararg words: String) = words.forEach { add(Keyword(it, it, cat)) }
        add(
            PlaceCategory.PHARMACY,
            "해열제", "감기약", "소화제", "진통제", "두통약", "콧물약", "기침약", "멀미약", "연고", "밴드", "반창고",
            "파스", "마스크", "체온계", "영양제", "타이레놀", "부루펜", "챔프", "인공눈물", "약",
        )
        add(
            PlaceCategory.CONVENIENCE,
            "우유", "생수", "라면", "아이스크림", "과자", "건전지", "음료수", "커피", "맥주", "삼각김밥", "도시락", "물",
        )
        add(
            PlaceCategory.MART,
            "계란", "달걀", "삼겹살", "고기", "채소", "야채", "과일", "두부", "기저귀", "분유", "휴지", "물티슈",
            "세제", "쌀", "장보기", "식재료",
        )
        add(PlaceCategory.SIDE_DISH, "반찬", "김치", "나물", "국거리", "밑반찬")
        add(PlaceCategory.BAKERY, "케이크", "식빵", "베이글", "빵")
    }.sortedByDescending { it.word.length }

    /** 문장에 장소 종류가 직접 나온 경우 ("약국에서 아무거나") */
    private val categoryWords: List<Pair<String, PlaceCategory>> = listOf(
        "약국" to PlaceCategory.PHARMACY,
        "편의점" to PlaceCategory.CONVENIENCE,
        "마트" to PlaceCategory.MART,
        "반찬가게" to PlaceCategory.SIDE_DISH,
        "빵집" to PlaceCategory.BAKERY,
        "베이커리" to PlaceCategory.BAKERY,
    )

    private val personWords = listOf(
        "할머니", "할아버지", "어머님", "아버님", "부모님", "남편", "아내", "엄마", "아빠", "아들", "딸", "동생", "친구",
        "아기", "애기", "아이", "애",
    )

    private val unit = "(개|팩|병|통|봉지|박스|묶음|캔|줄|판|알|봉|인분|kg|g|L|리터|장)"
    private val quantityRegex = Regex("(\\d+|한|두|세|네|다섯|여섯)\\s*$unit")
    private val modifierRegex = Regex("([가-힣A-Za-z]+)형(?=으로|이요|요|이|$|[^가-힣])")
    private val forKidRegex = Regex("아이|애기|아기|애가|아들|딸")

    /** 한 글자 키워드는 다른 낱말의 일부("물건", "약속", "빵빵")가 아닐 때만 인정한다 */
    private fun containsWord(text: String, word: String): Int {
        if (word.length > 1) return text.indexOf(word)
        val r = Regex("(^|[^가-힣])$word(?=$|[^가-힣]|[을를도좀이랑하과와])")
        return r.find(text)?.let { it.range.first + it.value.indexOf(word) } ?: -1
    }

    /** 유형 자동 분류 (F-02: 유형 미선택 시 자동 분류) */
    fun classify(text: String): ErrandType? {
        val t = text.replace(" ", "")
        fun has(vararg w: String) = w.any { t.contains(it.replace(" ", "")) }
        return when {
            has("데려와", "데리러", "데려다", "픽업", "태워와", "태우러", "마중") -> ErrandType.PICKUP_PERSON
            has("반찬") && has("들러", "들려", "부모님", "어머님", "엄마네", "본가") -> ErrandType.DROP_BY
            has("받아와", "받아다", "찾아와", "찾아다", "포장", "테이크아웃", "택배찾", "보관함") -> ErrandType.PICKUP_ITEM
            has("들러", "들려", "반납", "반품", "갖다줘", "가져다", "맡겨", "돌려줘", "돌려주") -> ErrandType.DROP_BY
            has("사와", "사다", "사줘", "사올", "사오", "구매", "사가") -> ErrandType.BUY
            buyKeywords.any { containsWord(text, it.word) >= 0 } -> ErrandType.BUY
            else -> null
        }
    }

    /**
     * 장소 이름에서 음성용 짧은 이름을 뽑는다: "한빛태권도" → "태권도", "할매김밥" → "김밥집".
     * 알려진 종류가 없으면 이름 그대로.
     */
    fun shortLabelOf(placeName: String): String {
        val n = placeName.replace(" ", "")
        val known = listOf(
            "태권도" to "태권도", "어린이집" to "어린이집", "유치원" to "유치원", "학원" to "학원", "초등학교" to "학교",
            "학교" to "학교", "소아과" to "소아과", "병원" to "병원", "수영" to "수영장", "피아노" to "피아노 학원",
            "김밥" to "김밥집", "치킨" to "치킨집", "피자" to "피자집", "세탁" to "세탁소", "약국" to "약국",
            "부모님" to "부모님 댁", "어머님" to "어머님 댁", "본가" to "본가",
        )
        return known.firstOrNull { n.contains(it.first) }?.second ?: placeName.trim()
    }

    fun interpret(text: String, selectedType: ErrandType? = null, placeName: String? = null): Interpretation {
        val clean = text.trim()
        val place = placeName?.trim()?.ifEmpty { null }
        val guessed = classify(clean)
        val type = selectedType ?: guessed ?: ErrandType.BUY
        val auto = selectedType == null
        return when (type) {
            ErrandType.BUY -> interpretBuy(clean, auto)
            ErrandType.PICKUP_ITEM -> interpretPickupItem(clean, place, auto)
            ErrandType.PICKUP_PERSON -> interpretPickupPerson(clean, place, auto)
            ErrandType.DROP_BY -> interpretDropBy(clean, place, auto)
        }
    }

    private fun interpretBuy(text: String, auto: Boolean): Interpretation {
        val match = buyKeywords
            .map { it to containsWord(text, it.word) }
            .filter { it.second >= 0 }
            .minByOrNull { it.second }
            ?.first
        val categoryInText = categoryWords.firstOrNull { text.contains(it.first) }?.second

        if (match == null) {
            if (categoryInText != null) {
                val item = "${categoryInText.label} 물건"
                return Interpretation(
                    ErrandType.BUY, auto, item, item, categoryInText, null, false,
                    "${Korean.euro(categoryInText.label)} 찾아볼게요 · 무엇을 살지 함께 적어 주면 좋아요",
                )
            }
            return Interpretation(
                ErrandType.BUY, auto, "", text.take(20), null, null,
                ambiguous = true,
                hint = if (text.isBlank()) "예) 아이 해열제, 우유 2팩" else "무엇을 사 올지 알아듣지 못했어요 · 예) 해열제, 우유 2팩",
            )
        }

        val modifier = modifierRegex.find(text)?.groupValues?.get(1)?.let { "${it}형" }
        val quantity = quantityRegex.find(text)?.value?.replace(" ", "")
        val base = match.item
        val item = buildString {
            append(base)
            if (modifier != null) append("($modifier)")
            if (quantity != null) append(" $quantity")
        }
        val forKid = match.category == PlaceCategory.PHARMACY && forKidRegex.containsMatchIn(text)
        val title = buildString {
            if (forKid) append("아이 ")
            append(base)
            if (modifier != null) append(" ($modifier)")
            if (quantity != null) append(" $quantity")
        }
        val category = categoryInText ?: match.category
        return Interpretation(
            ErrandType.BUY, auto, item, title, category, null, false,
            "${Korean.euro(category.label)} 찾아볼게요 · $item",
        )
    }

    private fun interpretPickupItem(text: String, place: String?, auto: Boolean): Interpretation {
        val t = text.replace(" ", "")
        val item = when {
            t.contains("포장") || t.contains("주문") || t.contains("테이크아웃") -> "포장 주문"
            t.contains("세탁") || t.contains("드라이") || t.contains("옷") -> "세탁물"
            t.contains("보관함") -> "보관함 물건"
            t.contains("택배") -> "택배"
            t.contains("반찬") -> "반찬"
            place?.contains("세탁") == true -> "세탁물"
            place != null && Regex("김밥|식당|치킨|피자|분식|반점").containsMatchIn(place) -> "포장 주문"
            else -> "맡겨 둔 물건"
        }
        val title = if (place != null) "$place $item" else item
        val hint = if (place != null) "차에서 Pleos 지도로 ${Korean.eul(place)} 찾아 들러요 · $item" else "받아 올 장소 이름을 적어 주세요"
        return Interpretation(ErrandType.PICKUP_ITEM, auto, item, title, null, null, false, hint)
    }

    private fun interpretPickupPerson(text: String, place: String?, auto: Boolean): Interpretation {
        val person = personWords
            .map { it to containsWord(text, it) }
            .filter { it.second >= 0 }
            .minByOrNull { it.second }
            ?.first
            ?.let { if (it == "애" || it == "애기" || it == "아기") "아이" else it }
            ?: "가족"
        val finished = Regex("끝나|끝난|마치|마친|끝").containsMatchIn(text)
        val title = when {
            place != null && finished -> "${shortLabelOf(place)} 끝난 $person 픽업"
            place != null -> "$place $person 픽업"
            else -> "$person 픽업"
        }
        val hint = if (place != null) {
            "${Korean.eseo(place)} $person 픽업 · 도착 예정 시각은 나에게만 보여요"
        } else {
            "데리러 갈 장소 이름과 시간을 정해 주세요"
        }
        return Interpretation(ErrandType.PICKUP_PERSON, auto, person, title, null, person, false, hint)
    }

    private fun interpretDropBy(text: String, place: String?, auto: Boolean): Interpretation {
        val t = text.replace(" ", "")
        val (item, titleCore) = when {
            t.contains("반찬통") -> "반찬통" to "반찬통 반납"
            t.contains("반찬") -> "반찬" to "반찬 챙겨가기"
            t.contains("반품") -> "반품 박스" to "반품 접수"
            t.contains("반납") || t.contains("돌려") -> "빌린 물건" to "빌린 물건 반납"
            t.contains("택배") -> "택배" to "택배 접수"
            else -> "부탁한 일" to (place?.let { "$it 들르기" } ?: "들르기")
        }
        val hint = if (place != null) "${place}에 들를게요 · $titleCore" else "들를 장소 이름을 적어 주세요"
        return Interpretation(ErrandType.DROP_BY, auto, item, titleCore, null, null, false, hint)
    }
}
