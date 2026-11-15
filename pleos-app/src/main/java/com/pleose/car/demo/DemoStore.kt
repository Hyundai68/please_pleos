package com.pleose.car.demo

import android.content.Context
import android.util.Log
import com.pleose.shared.demo.DemoPresets
import com.pleose.shared.demo.DemoScenario
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID

/**
 * 시연 시나리오 저장소. 기본 시나리오(기획안 시나리오 A·B 등)는 늘 있고, 사용자가 만든 시나리오는 기기에 저장한다.
 * PC에서 만든 JSON은 `adb push my.json /sdcard/Android/data/com.pleose.car/files/demo/`로 넣고 '파일에서 불러오기'를 누른다.
 */
class DemoStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("pleose_demo", Context.MODE_PRIVATE)
    /** 외부에서 넣는 JSON 폴더 */
    val importDir: File? = context.applicationContext.getExternalFilesDir("demo")

    private val _custom = MutableStateFlow(load())
    /** 사용자가 만든 시나리오 */
    val custom: StateFlow<List<DemoScenario>> = _custom.asStateFlow()

    val presets: List<DemoScenario> get() = DemoPresets.all

    fun all(): List<DemoScenario> = _custom.value + presets

    fun find(id: String): DemoScenario? = all().firstOrNull { it.id == id }

    fun isPreset(id: String): Boolean = presets.any { it.id == id }

    var selectedId: String
        get() = prefs.getString(KEY_SELECTED, null)?.takeIf { find(it) != null } ?: DemoPresets.scenarioA.id
        set(v) = prefs.edit().putString(KEY_SELECTED, v).apply()

    /** 저장 (기본 시나리오를 고치면 사본으로 저장) */
    fun save(s: DemoScenario): DemoScenario {
        val target = if (isPreset(s.id)) s.copy(id = newId(), title = s.title.takeIf { t -> presets.none { it.title == t } } ?: "${s.title} (내 사본)") else s
        val list = _custom.value.toMutableList()
        val i = list.indexOfFirst { it.id == target.id }
        if (i >= 0) list[i] = target else list.add(0, target)
        persist(list)
        return target
    }

    fun duplicate(s: DemoScenario): DemoScenario = save(s.copy(id = newId(), title = "${s.title} 사본"))

    fun delete(id: String) {
        persist(_custom.value.filter { it.id != id })
        if (selectedId == id) selectedId = DemoPresets.scenarioA.id
    }

    fun newBlank(): DemoScenario = save(DemoPresets.blank(newId()))

    /** JSON 하나(또는 배열)를 가져온다. 같은 id가 있으면 덮어쓴다 */
    fun importJson(text: String): List<DemoScenario> {
        val incoming = DemoScenario.fromJson(text).map { s -> if (isPreset(s.id) || s.id.isBlank()) s.copy(id = newId()) else s }
        val list = _custom.value.toMutableList()
        incoming.forEach { s ->
            val i = list.indexOfFirst { it.id == s.id }
            if (i >= 0) list[i] = s else list.add(0, s)
        }
        persist(list)
        return incoming
    }

    /** [importDir]의 *.json 파일을 모두 가져온다 */
    fun importFiles(): Result<Int> = runCatching {
        val dir = importDir ?: error("저장소를 쓸 수 없어요")
        dir.mkdirs()
        val files = dir.listFiles { f -> f.extension.equals("json", ignoreCase = true) }.orEmpty()
        files.sumOf { importJson(it.readText()).size }
    }

    fun exportJson(s: DemoScenario): String = DemoScenario.toJson(s)

    private fun persist(list: List<DemoScenario>) {
        _custom.value = list
        prefs.edit().putString(KEY_LIST, DemoScenario.toJson(list)).apply()
    }

    private fun load(): List<DemoScenario> {
        val raw = prefs.getString(KEY_LIST, null) ?: return emptyList()
        return runCatching { DemoScenario.fromJson(raw) }.onFailure { Log.w("pleose-Demo", "saved scenarios unreadable: ${it.message}") }.getOrDefault(emptyList())
    }

    private fun newId() = "my-" + UUID.randomUUID().toString().take(8)

    private companion object {
        const val KEY_LIST = "scenarios"
        const val KEY_SELECTED = "selected"
    }
}
