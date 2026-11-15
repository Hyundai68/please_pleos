package com.pleose.car.platform

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import ai.pleos.playground.stt.constant.ErrorType as SttErrorType
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInitializeListener
import ai.pleos.playground.tts.TextToSpeech as PleosTextToSpeech

enum class VoiceStatus { IDLE, SPEAKING, LISTENING }

/**
 * 음성 입출력 (F-04 음성 알림, F-05 음성 수락·거절).
 *
 * - 말하기: Pleos TextToSpeech SDK (차량 음성 엔진, pleos.car.permission.TTS_SERVICE). Pleos가 아닌 기기에서는 Android TTS
 * - 듣기: Pleos SpeechToText SDK (pleos.car.permission.STT_SERVICE).
 *   기본은 앱 등록 없이 쓰는 ON_DEVICE. Pleos 프로젝트 앱 등록(registerApp) 뒤에는 HYBRID로 바꿀 수 있다.
 *   음성 인식을 쓸 수 없으면 화면의 응답 칩이 음성 대신 쓰인다(에뮬레이터 테스트용).
 * - Gleo AI가 pleose 기능을 부르는 경로는 Gleo Interface([com.pleose.car.gleo.GleoActionReceiver])
 */
class VoiceIO(context: Context) : TextToSpeech.OnInitListener, VoicePort {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private val androidTts = TextToSpeech(appContext, this)
    private var androidReady = false
    private var pleosTts: PleosTextToSpeech? = null
    private var pleosReady = false

    private var stt: SpeechToText? = null
    private var sttReady = false
    private var sttMode = SttMode.ON_DEVICE
    /** 이번 질문에서 다시 듣기를 시도한 횟수 */
    private var restarts = 0
    /** 지금 듣고 있는 요청에 최종 결과를 넘기는 함수 */
    private var onSttFinal: ((List<String>?) -> Unit)? = null

    private val _status = MutableStateFlow(VoiceStatus.IDLE)
    override val status: StateFlow<VoiceStatus> = _status.asStateFlow()

    /** 마지막으로 들은 말 (화면 피드백용) */
    private val _heard = MutableStateFlow<String?>(null)
    override val heard: StateFlow<String?> = _heard.asStateFlow()

    private var speakToken = 0
    private var speakStartedAt = 0L
    private var pendingDone: (() -> Unit)? = null
    private var listenToken = 0

    val canListen: Boolean
        get() = sttReady &&
            ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Pleos TTS·STT 시작 (권한 요청 후 호출) */
    fun start() {
        startStt()
        startTts()
    }

    // region 말하기 (Pleos TextToSpeech)

    private fun startTts() {
        if (pleosTts != null) return
        try {
            val tts = PleosTextToSpeech(appContext, Mode.HYBRID)
            tts.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    // 국가까지 지정해야 한다: Locale.KOREAN("ko")은 Pleos 온디바이스 엔진이 "unsupported locale: ko-"로 거부한다
                    runCatching { tts.setLocale(Locale.KOREA) }
                    pleosReady = true
                    Log.i(TAG, "Pleos TTS ready")
                }

                override fun onFailure(errorType: ErrorType) {
                    Log.w(TAG, "Pleos TTS init failed: $errorType")
                }
            })
            tts.addEventListener(object : EventListener {
                override fun onStart() = Unit
                override fun onReady() = Unit
                override fun onDone() {
                    main.post { finishPleos() }
                }

                override fun onError(errorType: ErrorType) {
                    main.post { finishPleos() }
                }

                override fun onStop() = Unit
                override fun onUpdatedRms(rms: Double) = Unit
            })
            pleosTts = tts
        } catch (e: Throwable) {
            Log.w(TAG, "Pleos TTS unavailable: ${e.message}")
        }
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        val r = androidTts.setLanguage(Locale.KOREA)
        androidReady = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
        androidTts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                main.post { finishSpeaking(utteranceId?.toIntOrNull() ?: -1) }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                main.post { finishSpeaking(utteranceId?.toIntOrNull() ?: -1) }
            }
        })
    }

    /** 말하고, 끝나면 [onDone]. 엔진이 없거나 완료 신호가 오지 않아도 예상 시간 뒤에 반드시 한 번 호출된다. */
    override fun speak(text: String, onDone: () -> Unit) {
        stopListening()
        val token = ++speakToken
        pendingDone = onDone
        _status.value = VoiceStatus.SPEAKING
        speakStartedAt = System.currentTimeMillis()
        Log.i(TAG, "speak: $text")
        var spoken = false
        val pleos = pleosTts
        if (pleosReady && pleos != null) {
            spoken = runCatching {
                pleos.stop()
                pleos.speak(text)
            }.isSuccess
        }
        if (!spoken && androidReady) {
            spoken = androidTts.speak(text, TextToSpeech.QUEUE_FLUSH, null, token.toString()) == TextToSpeech.SUCCESS
        }
        // 한국어 TTS 대략 초당 6~7자 + 여유. 엔진이 없으면 같은 시간 동안 자막만 보여 준다.
        val estimate = 800L + text.length * 150L
        main.postDelayed({ finishSpeaking(token) }, if (spoken) estimate + 2_500 else estimate)
    }

    /** stop() 직후 앞 문장의 완료 신호가 늦게 올 수 있어, 막 시작한 문장은 끝난 것으로 보지 않는다 */
    private fun finishPleos() {
        if (System.currentTimeMillis() - speakStartedAt < 700) return
        finishSpeaking(speakToken)
    }

    private fun finishSpeaking(token: Int) {
        if (token != speakToken && token != -1) return
        if (_status.value != VoiceStatus.SPEAKING) return
        _status.value = VoiceStatus.IDLE
        val done = pendingDone
        pendingDone = null
        done?.invoke()
    }

    // endregion

    // region 듣기 (Pleos SpeechToText)

    private val sttListener = object : ResultListener {
        override fun onReady() = Unit
        override fun onStartedRecognition() = Unit
        override fun onUpdated(stt: String, completed: Boolean) {
            main.post {
                if (stt.isNotBlank()) _heard.value = stt
                if (completed && stt.isNotBlank()) onSttFinal?.invoke(listOf(stt))
            }
        }

        override fun onUpdatedEpdData(on: Long, off: Long) = Unit
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onEndedRecognition() {
            // 말이 끝났는데 최종 결과가 없으면 남은 시간 동안 다시 듣는다
            main.postDelayed({ restartListening() }, 300)
        }

        override fun onError(errorType: SttErrorType) {
            main.post { onSttError(errorType) }
        }
    }

    /**
     * 엔진이 없거나 인증이 안 되면 다시 시도하지 않는다:
     * ON_DEVICE 엔진 오류 → HYBRID로 한 번 바꿔 보고, 그래도 안 되면 음성 인식을 끄고 화면 칩으로 받는다.
     */
    private fun onSttError(e: SttErrorType) {
        Log.w(TAG, "STT error: $e (mode=$sttMode)")
        when (e) {
            SttErrorType.ON_DEVICE_ENGINE_ERROR, SttErrorType.UNAVAILABLE_LANGUAGE, SttErrorType.NOT_SUPPORTED_API ->
                if (sttMode == SttMode.ON_DEVICE) switchStt(SttMode.HYBRID) else disableStt()
            SttErrorType.UNAUTHENTICATED, SttErrorType.PERMISSION_DENIED, SttErrorType.MIC_ERROR, SttErrorType.AUDIO_ERROR ->
                disableStt()
            else -> main.postDelayed({ restartListening() }, 500)
        }
    }

    private fun switchStt(mode: SttMode) {
        releaseStt()
        sttMode = mode
        startStt()
    }

    private fun disableStt() {
        Log.w(TAG, "Pleos STT를 쓸 수 없어 화면 칩으로 응답을 받습니다")
        releaseStt()
        sttFailed = true
    }

    private var sttFailed = false

    private fun releaseStt() {
        sttReady = false
        runCatching { stt?.stop() }
        runCatching { stt?.removeListener(sttListener) }
        runCatching { stt?.release() }
        stt = null
    }

    private fun startStt() {
        if (stt != null || sttFailed) return
        val mode = sttMode
        try {
            val s = SpeechToText(appContext, mode)
            s.initialize(object : SttInitializeListener {
                override fun onSuccess() {
                    runCatching { s.setLocale(Locale.KOREA) }
                    sttReady = true
                    Log.i(TAG, "Pleos STT ready ($mode)")
                    // 모드를 바꾸는 사이에 듣고 있었으면 이어서 듣는다
                    if (_status.value == VoiceStatus.LISTENING && onSttFinal != null) runCatching { s.request() }
                }

                override fun onFailure(errorType: SttErrorType) {
                    Log.w(TAG, "Pleos STT init failed: $errorType")
                }
            })
            // 인식 이벤트를 놓치지 않도록 request() 전에 리스너를 등록한다
            s.addListener(sttListener)
            stt = s
        } catch (e: Throwable) {
            Log.w(TAG, "Pleos STT unavailable: ${e.message}")
        }
    }

    /**
     * [timeoutMs] 동안 듣는다. 인식 결과(문장)를 [onResult]로, 시간이 지나면 null을 준다.
     * 음성 인식을 쓸 수 없으면 듣는 상태만 유지해 화면 칩 입력을 기다린다.
     */
    override fun listen(timeoutMs: Long, onResult: (List<String>?) -> Unit) {
        val token = ++listenToken
        _heard.value = null // 이전 질문에서 들은 말은 지운다
        _status.value = VoiceStatus.LISTENING
        var finished = false
        val finish: (List<String>?) -> Unit = { r ->
            if (!finished && token == listenToken) {
                finished = true
                onSttFinal = null
                main.removeCallbacksAndMessages(LISTEN_TIMEOUT)
                runCatching { stt?.stop() }
                if (_status.value == VoiceStatus.LISTENING) _status.value = VoiceStatus.IDLE
                onResult(r)
            }
        }
        main.postAtTime({ finish(null) }, LISTEN_TIMEOUT, SystemClock.uptimeMillis() + timeoutMs)
        restarts = 0
        if (!canListen) return
        onSttFinal = finish
        runCatching { stt?.request() }.onFailure { Log.w(TAG, "STT request failed: ${it.message}") }
    }

    private fun restartListening() {
        if (_status.value != VoiceStatus.LISTENING || onSttFinal == null || !sttReady) return
        if (restarts++ >= MAX_RESTARTS) return // 남은 시간은 화면 칩을 기다린다
        runCatching { stt?.request() }
    }

    /** 화면 칩 등 다른 경로로 답을 받았을 때 듣기를 끝낸다 */
    override fun stopListening() {
        listenToken++
        onSttFinal = null
        main.removeCallbacksAndMessages(LISTEN_TIMEOUT)
        runCatching { stt?.stop() }
        if (_status.value == VoiceStatus.LISTENING) _status.value = VoiceStatus.IDLE
    }

    override fun setHeard(text: String) {
        _heard.value = text
    }

    // endregion

    fun shutdown() {
        stopListening()
        runCatching { stt?.removeListener(sttListener) }
        runCatching { stt?.release() }
        runCatching { pleosTts?.release() }
        androidTts.shutdown()
    }

    private companion object {
        const val TAG = "pleose-Voice"
        const val MAX_RESTARTS = 3
        val LISTEN_TIMEOUT = Any()
    }
}
