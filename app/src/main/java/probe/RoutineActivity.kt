package probe

import android.app.Activity
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*

/** 루틴 화면. 목록에서 실행, 편집에서 제목·단계 수정. 차량 화면이라 버튼 크게 */
class RoutineActivity : Activity() {
    private var routines = mutableListOf<Routine>()
    private lateinit var log: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var status: TextView
    private val dp by lazy { resources.displayMetrics.density }
    private fun px(v: Int) = (v * dp).toInt()
    private val ui = Handler(Looper.getMainLooper())

    // 배색
    private var night = false
    private val bg get() = if (night) 0xFF121315.toInt() else 0xFFFFFFFF.toInt()
    private val ink get() = if (night) 0xFFF2EFE8.toInt() else 0xFF17181A.toInt()
    private val mute get() = if (night) 0xFFA7A49C.toInt() else 0xFF5F6368.toInt()
    private val accent get() = if (night) 0xFFFF6B4A.toInt() else 0xFFD63A1F.toInt()
    private val onAccent get() = if (night) 0xFF1A0B07.toInt() else Color.WHITE

    // 실행 진행 표시
    // 상태줄(진행 중 n/m단계 · 남은 시간)용
    private var running = false
    private var runIdx = 0
    private var runTotal = 0
    private var waitEnd = 0L
    // 1초마다 남은 시간 갱신
    private val ticker = object : Runnable { override fun run() { paintStatus(); if (running) ui.postDelayed(this, 1000) } }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        // SDK 연결이 실패해도 화면은 떠야 해서 로그만 남김
        try { RoutineRunner.init(this) } catch (t: Throwable) { android.util.Log.i("ROUTINE", "SDK init 실패 $t") }
        night = Routines.night(this)
        routines = Routines.load(this).toMutableList()
        showList()
        offerResume()
    }

    /** 대기 중 앱이 꺼졌으면 이어서 할지 묻는다. 너무 오래 지났으면 버림 */
    private fun offerResume() {
        val p = Routines.pending(this) ?: return
        if (RoutineRunner.running()) return
        if (System.currentTimeMillis() > p.endMs + 15 * 60_000L) { Routines.clearPending(this); say("‘${p.title}’는 시간이 많이 지나 이어 실행하지 않았습니다"); return }
        sheet("‘${p.title}’ 루틴을 이어서 실행할까요?", "앱이 종료되기 전에 대기 중이었습니다. 남은 ${p.rest.size}단계를 이어서 실행합니다.", ok = "이어 실행", cancel = "그만두기",
            onCancel = { Routines.clearPending(this) }) {
            log.text = "▶ ‘${p.title}’ 이어 실행"
            running = true; runIdx = 0; runTotal = p.rest.size; waitEnd = p.endMs
            ui.removeCallbacks(ticker); ui.post(ticker)
            RoutineRunner.resume(p, outFor(p.title))
        }
    }

    // 작은 부품
    private fun txt(t: String, sp: Float, color: Int = ink, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = sp; setTextColor(color); if (bold) setTypeface(null, Typeface.BOLD)
    }
    private fun rule(thick: Int, color: Int = ink) = View(this).apply { setBackgroundColor(color); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(thick)) }
    /** kind: 0 = 포인트(채움), 1 = 테두리, 2 = 글자만 */
    private fun btn(text: String, sp: Float = 20f, kind: Int = 1, fg: Int = ink, h: Int = 52, onClick: () -> Unit) = Button(this).apply {
        this.text = text; textSize = sp; isAllCaps = false; minHeight = px(h); minimumHeight = px(h); minWidth = 0; minimumWidth = 0
        setPadding(px(14), px(6), px(14), px(6)); stateListAnimator = null
        when (kind) {
            0 -> { setTextColor(onAccent); setTypeface(null, Typeface.BOLD); background = GradientDrawable().apply { cornerRadius = px(8).toFloat(); setColor(accent) } }
            1 -> { setTextColor(fg); background = GradientDrawable().apply { cornerRadius = px(8).toFloat(); setColor(Color.TRANSPARENT); setStroke(px(2), fg) } }
            else -> { setTextColor(fg); background = null }
        }
        setOnClickListener { onClick() }
    }
    private fun lp(w: Int = M, h: Int = W, weight: Float = 0f, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) =
        LinearLayout.LayoutParams(w, h, weight).apply { setMargins(px(l), px(t), px(r), px(b)) }
    private val M = LinearLayout.LayoutParams.MATCH_PARENT
    private val W = LinearLayout.LayoutParams.WRAP_CONTENT

    private fun page() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(26), px(18), px(26), px(12)); setBackgroundColor(bg) }


    /** 확인창. ok 가 null 이면 닫기만 */
    private fun sheet(title: String, message: String? = null, body: View? = null, ok: String? = null, cancel: String? = "취소",
                      extra: String? = null, onExtra: () -> Unit = {}, onCancel: () -> Unit = {}, onOk: () -> Unit = {}) {
        val d = android.app.Dialog(this)
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(px(28), px(24), px(28), px(20))
            background = GradientDrawable().apply { cornerRadius = px(12).toFloat(); setColor(bg); setStroke(px(3), ink) }
        }
        card.addView(txt(title, 26f, ink, true))
        card.addView(rule(3), lp(M, px(3), 0f, 0, 12, 0, 14))
        if (message != null) card.addView(txt(message, 20f, ink).apply { setLineSpacing(0f, 1.25f) }, lp(M, W, 0f, 0, 0, 0, 18))
        if (body != null) card.addView(body, lp(M, W, 0f, 0, 0, 0, 18))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        if (extra != null) row.addView(btn(extra, 20f, 2, mute, 56) { d.dismiss(); onExtra() }, lp(W, W, 0f, 0, 0, 8, 0))
        if (cancel != null && ok != null) row.addView(btn(cancel, 20f, 1, ink, 56) { d.dismiss(); onCancel() }, lp(W, W, 0f, 0, 0, 12, 0))
        if (ok != null) row.addView(btn(ok, 22f, 0, ink, 56) { d.dismiss(); onOk() }) else row.addView(btn("확인", 22f, 0, ink, 56) { d.dismiss() })
        card.addView(row)
        d.setContentView(card)
        d.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            setLayout(px(560), LinearLayout.LayoutParams.WRAP_CONTENT)
            setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        shown = d
        d.show()
    }
    private var shown: android.app.Dialog? = null

    // 목록
    private fun showList() {
        val root = page()
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        head.addView(txt("루틴", 40f, bold = true), lp(0, W, 1f))
        head.addView(btn(if (night) "낮 모드" else "밤 모드", 14f, 2, mute, 44) { night = !night; Routines.setNight(this, night); showList() })
        head.addView(btn("기본으로 되돌리기", 14f, 2, ink, 44) {
            sheet("기본으로 되돌릴까요?", "만든 루틴이 모두 사라지고 기본 루틴 3개로 돌아갑니다.", ok = "되돌리기") { Routines.reset(this); routines = Routines.load(this).toMutableList(); showList() }
        }.also { (it as TextView).paintFlags = it.paintFlags or Paint.UNDERLINE_TEXT_FLAG })
        root.addView(head)
        root.addView(rule(4), lp(M, px(4), 0f, 0, 6))

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (r in routines) {
            list.addView(txt(r.title, 28f, bold = true), lp(t = 16))
            list.addView(txt(r.steps.joinToString(" → ") { Routines.describe(it) }, 15f, mute).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }, lp(t = 4, b = 10))
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(btn("▶  실행", 22f, 0, h = 56) { runRoutine(r) }, lp(0, W, 2f, r = 8))
            row.addView(btn("편집", 17f) { showEditor(r) }, lp(0, W, 1f, r = 8))
            row.addView(btn("삭제", 17f) {
                sheet("‘${r.title}’ 루틴을 삭제할까요?", "삭제하면 되돌릴 수 없습니다.", ok = "삭제") { routines.remove(r); Routines.save(this, routines); showList() }
            }, lp(0, W, 1f))
            list.addView(row)
            list.addView(rule(1), lp(M, px(1), 0f, 0, 16))
        }
        list.addView(btn("＋  새 루틴 만들기", 22f, 2, ink, 60) { showEditor(null) }.also { (it as Button).gravity = Gravity.START or Gravity.CENTER_VERTICAL; it.setTypeface(null, Typeface.BOLD) }, lp(t = 6))
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(M, 0, 1f))

        root.addView(rule(4))
        val logHead = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        logHead.addView(txt("실행 결과", 15f, mute, true), lp(0, W, 1f))
        logHead.addView(btn("중지", 15f, 1, ink, 44) { RoutineRunner.cancel(); stopTicker(); say("— 중지했습니다") })
        root.addView(logHead, lp(t = 4))
        status = txt("", 17f, accent, true)
        root.addView(status)
        log = txt("실행하면 결과가 여기에 한 줄씩 나옵니다.", 15f, ink).apply { setPadding(0, px(4), 0, px(4)) }
        logScroll = ScrollView(this).apply { addView(log) }
        root.addView(logScroll, LinearLayout.LayoutParams(M, px(104)))
        setContentView(root)
        paintStatus()
    }

    private fun say(s: String) { log.append("\n$s"); logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) } }

    private fun stopTicker() { running = false; waitEnd = 0L; ui.removeCallbacks(ticker); paintStatus() }
    private fun paintStatus() {
        if (!::status.isInitialized) return
        if (!running) { status.visibility = View.GONE; return }
        status.visibility = View.VISIBLE
        var t = "진행 중 · $runIdx/${runTotal}단계"
        if (waitEnd > 0) { val left = Math.max(0L, (waitEnd - System.currentTimeMillis()) / 1000); t += " · 남은 시간 %d:%02d".format(left / 60, left % 60) }
        status.text = t
    }

    private fun runRoutine(r: Routine) {
        log.text = "▶ ‘${r.title}’ 시작"
        running = true; runIdx = 0; runTotal = r.steps.size; waitEnd = 0L
        ui.removeCallbacks(ticker); ui.post(ticker)
        RoutineRunner.run(r.title, r.steps, outFor(r.title))
    }

    // 실행/이어실행 둘 다 같은 방식으로 결과를 받는다
    private fun outFor(title: String) = object : RoutineRunner.Out {
        override fun line(s: String) = say(s)
        override fun begin(index: Int, s: Step) { runIdx = index + 1; waitEnd = if (s.type == "wait_min") System.currentTimeMillis() + s.value * 60_000L else 0L; paintStatus() }
        override fun finished(cancelled: Boolean) { stopTicker(); say(if (cancelled) "■ 중지됨" else "■ ‘$title’ 끝") }
    }

    // 편집
    // draft: 기능 추가 화면을 다녀온 뒤 편집 중이던 단계와 제목을 되살리는 용
    private fun showEditor(orig: Routine?, draft: MutableList<Step>? = null, draftTitle: String? = null) {
        val steps = draft ?: (orig?.steps ?: emptyList()).toMutableList()
        val root = page()
        root.addView(txt(if (orig == null) "새 루틴" else "루틴 편집", 15f, mute, true))
        val title = EditText(this).apply {
            setText(draftTitle ?: orig?.title ?: ""); hint = "루틴 이름 (예: 등하원)"; textSize = 30f; setTypeface(null, Typeface.BOLD); setSingleLine()
            setTextColor(ink); setHintTextColor(mute)
        }
        root.addView(title, lp(t = 2))
        root.addView(rule(4))
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(box) }, LinearLayout.LayoutParams(M, 0, 1f))

        fun redraw() {
            box.removeAllViews()
            steps.forEachIndexed { i, s ->
                val d = Routines.def(s.type)
                val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = px(64) }
                top.addView(txt("${i + 1}. ${Routines.describe(s)}", 19f).apply {
                    if (d.kind == Kind.NAVI || d.kind == Kind.TEXT || d.kind == Kind.APP) { setTypeface(null, Typeface.BOLD); paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG; setOnClickListener { if (d.kind == Kind.APP) pickApp(s) { n -> steps[i] = n; redraw() } else editText(s) { n -> steps[i] = n; redraw() } } }
                }, lp(0, W, 1f, r = 6))
                // ＋/－: 내비는 경로 옵션을 돌려 가며, 나머지는 step 만큼 값 조절
                fun change(delta: Int) {
                    steps[i] = when (d.kind) {
                        Kind.NAVI -> { val k = Routines.naviOptions.indexOfFirst { it.first == s.opt }.coerceAtLeast(0); s.copy(opt = Routines.naviOptions[(k + delta + Routines.naviOptions.size) % Routines.naviOptions.size].first) }
                        Kind.TEXT, Kind.APP -> s
                        else -> {
                            var v = s.value + delta * d.step
                            // 기다리기는 1분에서 －를 누르면 60분, 60분에서 ＋를 누르면 1분으로 돌아간다
                            if (d.type == "wait_min") { if (v < d.min) v = d.max else if (v > d.max) v = d.min }
                            s.copy(value = v.coerceIn(d.min, d.max))
                        }
                    }; redraw()
                }
                if (d.kind != Kind.TEXT && d.kind != Kind.APP) {
                    top.addView(btn("－", 24f, 1, h = 52) { change(-1) }, lp(px(58), W, r = 6))
                    top.addView(btn("＋", 24f, 1, h = 52) { change(1) }, lp(px(58), W, r = 6))
                }
                top.addView(btn("▲", 16f, 2, mute, 52) { if (i > 0) { steps.add(i - 1, steps.removeAt(i)); redraw() } }, lp(px(46), W))
                top.addView(btn("▼", 16f, 2, mute, 52) { if (i < steps.size - 1) { steps.add(i + 1, steps.removeAt(i)); redraw() } }, lp(px(46), W))
                top.addView(btn("✕", 20f, 2, ink, 52) { steps.removeAt(i); redraw() }, lp(px(50), W))
                box.addView(top); box.addView(rule(1))
            }
            if (steps.isEmpty()) box.addView(txt("아직 기능이 없습니다. 아래 ‘기능 추가’를 눌러 조합해 보세요.", 16f, mute).apply { setPadding(0, px(18), 0, px(18)) })
        }
        redraw()
        root.addView(btn("＋  기능 추가", 22f, 2, ink, 60) {
            pickerBack = { showEditor(orig, steps, title.text.toString()) }
            showPicker { type -> steps.add(Routines.newStep(type)); showEditor(orig, steps, title.text.toString()) }
        }.also { (it as Button).gravity = Gravity.START or Gravity.CENTER_VERTICAL; it.setTypeface(null, Typeface.BOLD) })
        root.addView(rule(4), lp(M, px(4), 0f, 0, 4))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(btn("취소", 22f, 1, h = 60) { showList() }, lp(0, W, 1f, t = 10, r = 8))
        row.addView(btn("저장", 24f, 0, h = 60) {
            val name = title.text.toString().trim()
            if (name.isEmpty()) { sheet("루틴 이름을 적어 주세요", cancel = null); return@btn }
            if (steps.isEmpty()) { sheet("기능을 하나 이상 넣어 주세요", cancel = null); return@btn }
            // 새 루틴이면 시간으로 id 를 만들고, 기존이면 같은 id 자리를 교체
            val r = Routine(orig?.id ?: "r" + System.currentTimeMillis(), name, steps.toList())
            val at = routines.indexOfFirst { it.id == r.id }
            if (at >= 0) routines[at] = r else routines.add(r)
            Routines.save(this, routines); showList()
        }, lp(0, W, 2f, t = 10))
        root.addView(row)
        setContentView(root)
    }

    // 기능 추가 (분류별 목록)
    // 기능 추가 화면 ‘닫기’ 때 돌아갈 곳
    private var pickerBack: () -> Unit = {}
    // 기능 추가 화면의 분류
    private val groups = listOf(
        "이동" to listOf("navi"),
        "공조" to listOf("hvac_power", "hvac_auto", "hvac_ac", "hvac_temp", "hvac_fan", "hvac_recirc", "air_cleaner"),
        "시트" to listOf("seat_heat", "wheel_heat", "seat_recline"),
        "조명" to listOf("cabin_light", "reading_rear", "ambient"),
        "흐름" to listOf("wait_min", "notify"),
        "앱" to listOf("app"))

    private fun showPicker(pick: (String) -> Unit) {
        val root = page()
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(txt("추가할 기능", 32f, bold = true), lp(0, W, 1f))
        head.addView(btn("닫기", 18f, 1, h = 48) { pickerBack() })
        root.addView(head)
        root.addView(rule(4), lp(M, px(4), 0f, 0, 8))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for ((g, types) in groups) {
            col.addView(txt(g, 15f, mute, true), lp(t = 16, b = 4))
            col.addView(rule(2))
            for (t in types) {
                val d = Routines.def(t)
                col.addView(btn(d.label, 21f, 2, ink, 58) { pick(t) }.also { (it as Button).gravity = Gravity.START or Gravity.CENTER_VERTICAL; it.setPadding(0, 0, 0, 0) }, lp())
                col.addView(rule(1))
            }
        }
        root.addView(ScrollView(this).apply { addView(col) }, LinearLayout.LayoutParams(M, 0, 1f))
        setContentView(root)
    }

    /** 설치된 앱 중에서 루틴이 실행할 앱을 고른다. */
    private fun pickApp(s: Step, done: (Step) -> Unit) {
        val pm = packageManager
        val apps = pm.queryIntentActivities(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != packageName }.distinctBy { it.first }.sortedBy { it.second }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (apps.isEmpty()) col.addView(txt("실행할 수 있는 앱이 없습니다", 18f, mute))
        for ((pkg, label) in apps) {
            col.addView(btn(label, 20f, 2, ink, 52) { shown?.dismiss(); done(s.copy(arg = pkg, opt = label)) }.also { (it as Button).gravity = Gravity.START or Gravity.CENTER_VERTICAL; it.setPadding(0, 0, 0, 0) }, lp())
            col.addView(rule(1))
        }
        val list = object : ScrollView(this) {
            override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, android.view.View.MeasureSpec.makeMeasureSpec(px(380), android.view.View.MeasureSpec.AT_MOST))
        }.apply { addView(col) }
        sheet("실행할 앱", body = list, ok = "닫기", cancel = null)
    }

    /** 내비 목적지(즐겨찾기 이름) 또는 알림 문구를 입력한다. */
    private fun editText(s: Step, done: (Step) -> Unit) {
        val navi = s.type == "navi"
        val input = EditText(this).apply {
            setText(s.arg); inputType = InputType.TYPE_CLASS_TEXT; textSize = 22f; setTextColor(ink); setHintTextColor(mute); setSingleLine()
            hint = if (navi) "차량 내비 즐겨찾기 이름 (예: 회사, 집)" else "알림 문구"
            setPadding(px(4), px(10), px(4), px(10))
            background = null
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(input); box.addView(rule(2), lp(M, px(2)))
        sheet(if (navi) "내비 목적지" else "알림 문구", body = box, ok = "확인", extra = if (navi) "집" else null,
            onExtra = { done(s.copy(arg = "집")) }) { done(s.copy(arg = input.text.toString().trim())) }
    }

    override fun onDestroy() { super.onDestroy(); ui.removeCallbacks(ticker) }
}
