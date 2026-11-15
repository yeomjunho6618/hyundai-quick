package probe

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 루틴 = 제목 + 실행할 단계 목록 */
// value: 온도는 x10 정수(22.5도 = 225), 켜기/끄기는 1/0, 등받이는 +눕힘 -세움(초)
// arg: 내비는 즐겨찾기 이름, 알림은 문구, 앱 실행은 패키지 이름 / opt: 내비 경로 옵션, 앱 실행은 앱 이름
data class Step(val type: String, val value: Int = 0, val arg: String = "", val opt: String = "")
data class Routine(val id: String, val title: String, val steps: List<Step>)

/** 기능 종류. min..max 를 step 씩 조절. ONOFF 는 0=끄기 1=켜기 */
data class Def(val type: String, val label: String, val min: Int, val max: Int, val step: Int, val def: Int, val kind: Kind = Kind.NUM, val unit: String = "")
enum class Kind { NUM, ONOFF, TEMP, RECLINE, NAVI, TEXT, APP }

object Routines {
    // 기능 추가할 땐 여기 한 줄 + RoutineRunner.exec 에 case 한 줄
    val catalog = listOf(
        Def("navi", "내비 목적지", 0, 2, 1, 0, Kind.NAVI),
        Def("hvac_power", "공조 전원", 0, 1, 1, 1, Kind.ONOFF),
        Def("hvac_temp", "온도", 150, 300, 5, 220, Kind.TEMP),
        Def("hvac_fan", "풍량", 1, 8, 1, 3, unit = "단"),
        Def("hvac_auto", "오토 공조", 0, 1, 1, 1, Kind.ONOFF),
        Def("hvac_ac", "에어컨", 0, 1, 1, 1, Kind.ONOFF),
        Def("hvac_recirc", "내기순환", 0, 1, 1, 1, Kind.ONOFF),
        Def("air_cleaner", "공기청정", 0, 1, 1, 1, Kind.ONOFF),
        Def("seat_heat", "운전석 열선", 0, 3, 1, 2, unit = "단"),
        Def("wheel_heat", "핸들 열선", 0, 3, 1, 1, unit = "단"),
        Def("cabin_light", "실내등", 0, 1, 1, 1, Kind.ONOFF),
        Def("reading_rear", "뒷좌석 독서등", 0, 1, 1, 1, Kind.ONOFF),
        Def("ambient", "앰비언트 밝기", 0, 100, 10, 30, unit = "%"),
        Def("seat_recline", "운전석 등받이", -10, 10, 1, 5, Kind.RECLINE),
        Def("wait_min", "기다리기", 1, 60, 1, 10, unit = "분"),
        Def("notify", "알림 보내기", 0, 0, 1, 0, Kind.TEXT),
        Def("app", "앱 실행", 0, 0, 1, 0, Kind.APP),
    )
    private val byType = catalog.associateBy { it.type }
    fun def(type: String) = byType.getValue(type)
    val naviOptions = listOf("RECOMMENDED" to "추천 경로", "SCHOOL_ZONE" to "스쿨존 우선", "SHORT_TIME" to "최소 시간")

    fun newStep(type: String): Step { val d = def(type); return Step(type, d.def, if (type == "notify") "알림" else "", if (type == "navi") "RECOMMENDED" else "") }

    fun describe(s: Step): String {
        val d = def(s.type)
        return when (d.kind) {
            Kind.ONOFF -> "${d.label} ${if (s.value == 1) "켜기" else "끄기"}"
            Kind.TEMP -> "${d.label} ${"%.1f".format(s.value / 10.0)}℃"
            Kind.RECLINE -> if (s.value == 0) "${d.label} 그대로" else "${d.label} ${if (s.value > 0) "눕히기" else "세우기"} ${Math.abs(s.value)}초"
            Kind.NAVI -> "내비 목적지 ‘${s.arg.ifBlank { "(정해 주세요)" }}’ · ${naviOptions.firstOrNull { it.first == s.opt }?.second ?: "추천 경로"}"
            Kind.TEXT -> "알림 ‘${s.arg}’"
            Kind.APP -> "앱 실행 ‘${s.opt.ifBlank { "(정해 주세요)" }}’"
            Kind.NUM -> if (d.type == "ambient" && s.value == 0) "${d.label} 끄기" else if (d.type.endsWith("heat") && s.value == 0) "${d.label} 끄기" else "${d.label} ${s.value}${d.unit}"
        }
    }

    /** 기본 루틴. 내비는 즐겨찾기 이름으로 찾음 */
    fun defaults(): List<Routine> = listOf(
        Routine("school", "등하원", listOf(
            Step("navi", 0, "", "SCHOOL_ZONE"), Step("cabin_light", 1), Step("reading_rear", 1),
            Step("hvac_power", 1), Step("hvac_temp", 225), Step("hvac_fan", 3))),
        Routine("work", "출근", listOf(
            Step("navi", 0, "회사", "RECOMMENDED"), Step("hvac_power", 1), Step("hvac_auto", 1), Step("hvac_temp", 220),
            Step("seat_heat", 2), Step("wheel_heat", 1))),
        Routine("rest", "휴식 10분", listOf(
            Step("hvac_power", 1), Step("hvac_temp", 240), Step("hvac_fan", 2), Step("ambient", 20), Step("seat_recline", 6),
            Step("notify", 0, "휴식을 시작합니다. 10분 뒤에 알려드릴게요."), Step("wait_min", 10),
            Step("seat_recline", -6), Step("ambient", 0), Step("notify", 0, "10분이 지났어요. 시트를 세웠습니다."))),
    )

    // 저장
    // 루틴 목록은 JSON 문자열 하나로 저장
    private fun prefs(c: Context) = c.getSharedPreferences("routines", Context.MODE_PRIVATE)
    fun load(c: Context): List<Routine> {
        val raw = prefs(c).getString("list", null) ?: return defaults()
        // 저장값이 깨졌으면 기본 루틴으로
        return try { parse(raw) } catch (_: Exception) { defaults() }
    }
    fun save(c: Context, list: List<Routine>) { prefs(c).edit().putString("list", toJson(list)).apply() }
    fun reset(c: Context) { prefs(c).edit().remove("list").apply() }
    fun night(c: Context) = prefs(c).getBoolean("night", false)
    fun setNight(c: Context, v: Boolean) { prefs(c).edit().putBoolean("night", v).apply() }

    // 기다리는 중 앱이 꺼져도 이어가려고 남은 단계/끝 시각 저장
    class Pending(val title: String, val rest: List<Step>, val endMs: Long)
    // apply 는 비동기라 바로 앱이 죽으면 날아가서 commit
    fun savePending(c: Context, title: String, rest: List<Step>, endMs: Long) {
        val steps = toJson(listOf(Routine("p", title, rest)))
        prefs(c).edit().putString("pending", steps).putLong("pendingEnd", endMs).commit()
    }
    fun clearPending(c: Context) { prefs(c).edit().remove("pending").remove("pendingEnd").commit() }
    fun pending(c: Context): Pending? {
        val raw = prefs(c).getString("pending", null) ?: return null
        return try { val r = parse(raw)[0]; Pending(r.title, r.steps, prefs(c).getLong("pendingEnd", 0L)) } catch (_: Exception) { null }
    }

    fun toJson(list: List<Routine>): String = JSONArray().also { a ->
        for (r in list) a.put(JSONObject().put("id", r.id).put("title", r.title).put("steps", JSONArray().also { sa ->
            for (s in r.steps) sa.put(JSONObject().put("type", s.type).put("value", s.value).put("arg", s.arg).put("opt", s.opt))
        }))
    }.toString()

    fun parse(raw: String): List<Routine> {
        // 카탈로그에 없는 type 은 버린다 (옛 데이터 대비)
        val a = JSONArray(raw); val out = ArrayList<Routine>()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i); val sa = o.getJSONArray("steps"); val steps = ArrayList<Step>()
            for (j in 0 until sa.length()) { val s = sa.getJSONObject(j); if (byType.containsKey(s.getString("type"))) steps.add(Step(s.getString("type"), s.optInt("value"), s.optString("arg"), s.optString("opt"))) }
            out.add(Routine(o.getString("id"), o.getString("title"), steps))
        }
        return out
    }
}
