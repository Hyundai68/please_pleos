package com.pleose.car.agent

import com.pleose.shared.model.Errand
import com.pleose.shared.route.ErrandPlan
import com.pleose.shared.voice.Reply
import com.pleose.shared.voice.Script

/**
 * 지금 운전자에게 묻고 있는 음성 질문 하나.
 * 새 부탁에는 수락·조건부 수락·거절 세 버튼으로 답하고(기획안 3.2), 진행 중인 부탁은 진행·취소·완료·경로 재검색의 순서를 지킨다.
 */
sealed interface Prompt {
    val id: Long
    val script: Script
    /** 화면 버튼 (왼쪽부터) */
    val replies: List<Reply>
    val errandIds: List<String>

    /** 음성·Gleo로도 받는 답 (버튼 + 말로만 하는 답) */
    val voiceReplies: List<Reply> get() = replies

    /** 답 버튼 글씨 */
    fun label(reply: Reply): String = reply.label

    /** 지금 고를 수 있는 답인지 (조건부 수락은 늦을 때만) */
    fun enabled(reply: Reply): Boolean = true

    /** 버튼 아래 작은 글씨: 말로 하는 법 */
    fun hint(reply: Reply): String? = null

    /** 새 부탁 안내 (화면 시안 ①, 디자인 03·07): 수락 · 조건부 수락 · 거절 */
    data class Offer(
        override val id: Long,
        val errand: Errand,
        override val script: Script,
        /** 들를 곳을 미리 알 때 — 도착 예정·추가 시간 */
        val plan: ErrandPlan? = null,
        /** 약속보다 늦는 분 — 있으면 조건부 수락을 쓸 수 있다 */
        val lateMin: Int? = null,
    ) : Prompt {
        override val replies: List<Reply> get() = ANSWERS
        override val voiceReplies: List<Reply> get() = ANSWERS + listOf(Reply.LATER, Reply.OTHER)
        override val errandIds: List<String> get() = listOf(errand.id)
        override fun label(reply: Reply): String = answerLabel(reply)
        override fun enabled(reply: Reply): Boolean = when (reply) {
            Reply.CONDITIONAL -> lateMin != null
            Reply.OTHER -> (plan?.options?.size ?: 0) > 1
            else -> true
        }
        override fun hint(reply: Reply): String? = answerHint(reply, lateMin)
    }

    /** 여러 부탁 한 번에 안내 (화면 시안 ②, 디자인 03). "하나씩" = 따로 묻기 */
    data class Batch(
        override val id: Long,
        val errands: List<Errand>,
        override val script: Script,
        val plans: Map<String, ErrandPlan> = emptyMap(),
        /** 약속보다 늦는 부탁 → 분 */
        val late: Map<String, Int> = emptyMap(),
    ) : Prompt {
        override val replies: List<Reply> get() = ANSWERS
        override val voiceReplies: List<Reply> get() = ANSWERS + listOf(Reply.OTHER, Reply.LATER)
        override val errandIds: List<String> get() = errands.map { it.id }
        override fun label(reply: Reply): String = if (reply == Reply.OTHER) "하나씩" else answerLabel(reply)
        override fun enabled(reply: Reply): Boolean = reply != Reply.CONDITIONAL || late.isNotEmpty()
        override fun hint(reply: Reply): String? = answerHint(reply, late.values.maxOrNull())
    }

    /** 경유지 출발 후 완료 확인 (화면 시안 ④) */
    data class Completion(override val id: Long, val errand: Errand, override val script: Script) : Prompt {
        override val replies: List<Reply> get() = listOf(Reply.NOT_DONE, Reply.YES, Reply.RESEARCH)
        override val errandIds: List<String> get() = listOf(errand.id)
        override fun label(reply: Reply): String = if (reply == Reply.YES) "완료" else reply.label
    }

    /**
     * 부탁을 끝낸 뒤 또는 주행 중 Please 버튼을 눌렀을 때: 다음 부탁을 할지 묻는다.
     * "진행" = 시작, "다른 부탁" = 다른 부탁 듣기, "나중에" = 하지 않고 버튼에 남겨 두기. 답이 없어도 그대로 남는다.
     */
    data class NextQuest(override val id: Long, val errand: Errand, override val script: Script) : Prompt {
        override val replies: List<Reply> get() = listOf(Reply.YES, Reply.OTHER, Reply.LATER)
        override val errandIds: List<String> get() = listOf(errand.id)
        override fun label(reply: Reply): String = if (reply == Reply.OTHER) "다른 부탁" else reply.label
    }

    private companion object {
        /** 새 부탁의 세 가지 답 (기획안 3.2) */
        val ANSWERS = listOf(Reply.YES, Reply.CONDITIONAL, Reply.NO)

        fun answerLabel(reply: Reply): String = when (reply) {
            Reply.YES -> "수락"
            Reply.CONDITIONAL -> "조건부 수락"
            Reply.NO -> "거절"
            else -> reply.label
        }

        fun answerHint(reply: Reply, lateMin: Int?): String? = when (reply) {
            Reply.YES -> "“응, 안내해 줘”"
            Reply.CONDITIONAL -> if (lateMin != null) "“${lateMin}분 늦어도 되는지 물어봐 줘”" else "늦지 않아 조건이 필요 없어요"
            Reply.NO -> "“지금은 어려워”"
            else -> null
        }
    }
}
