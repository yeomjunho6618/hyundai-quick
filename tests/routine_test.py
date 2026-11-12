"""루틴 앱 테스트: 여러 기능 조합으로 루틴을 만들어 넣고, 앱 화면에서 ▶ 실행을 눌러 실제로 돌린 뒤
(1) 화면 실행 결과(✓/✕ 줄·끝 표시)와 (2) 에뮬레이터 차량 상태(콘솔 HTTP)를 기대값과 비교한다.

루틴은 앱의 SharedPreferences(run-as)에 JSON으로 직접 써 넣는다 — 화면에서 만드는 흐름은 별도 UI 케이스(ui_*)로 확인.
사용: python3 tests/routine_test.py [케이스이름 ...]   (인자 없으면 전부; --fast 는 1분 대기가 있는 케이스 제외)
"""
import json, os, re, subprocess, sys, time, urllib.request
from xml.sax.saxutils import escape

B = "http://127.0.0.1:8088"
ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
PKG = "probe.reversemap"

def adb(*a, inp=None):
    return subprocess.run([ADB, *a], capture_output=True, text=True, input=inp).stdout
def sh(*a): return adb("shell", *a)
import threading
def _heartbeat():
    while True:   # 콘솔은 브라우저 세션 하트비트가 있어야 제어 PUT 을 받는다 (없으면 503)
        try: urllib.request.urlopen(urllib.request.Request(B + "/browser/session?client=x", method="POST"), timeout=3).read()
        except Exception: pass
        time.sleep(1)
threading.Thread(target=_heartbeat, daemon=True).start(); time.sleep(2)
def put(p): return urllib.request.urlopen(urllib.request.Request(B + p, method="PUT"), timeout=10).read().decode()
def state(): return json.load(urllib.request.urlopen(B + "/vehicle/state", timeout=10))

# ---------------- 화면 읽기/누르기 (FLAG_SECURE 라 uiautomator dump 사용) ----------------
def nodes():
    for _ in range(6):
        sh("rm", "-f", "/sdcard/ui.xml"); sh("uiautomator", "dump", "/sdcard/ui.xml"); x = sh("cat", "/sdcard/ui.xml")
        if "<hierarchy" in x and 'package="probe.reversemap"' in x: break
        if "<hierarchy" in x: sh("input", "tap", "447", "115")   # 다른 창(드라이빙뷰 등)이 포커스를 가지면 앱 머리글을 눌러 포커스를 되찾는다
        time.sleep(1)
    return [(m.group(1).replace("&#10;", "\n"), tuple(map(int, m.groups()[1:])))
            for m in re.finditer(r'text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', x) if m.group(1)]
def screen(): return "\n".join(t for t, _ in nodes())
def tap(label, nth=0):
    hits = [b for t, b in nodes() if t == label or t.startswith(label)]
    if len(hits) <= nth: return False
    b = hits[nth]; sh("input", "tap", str((b[0] + b[2]) // 2), str((b[1] + b[3]) // 2)); return True

# ---------------- 루틴 주입 ----------------
def write_routines(routines):
    """routines: [(id,title,[step...])], step = dict(type,value,arg,opt). None 이면 저장값을 지워 기본 루틴이 뜨게 한다."""
    if routines is None:
        xml = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map />\n"
    else:
        js = json.dumps([{"id": i, "title": t, "steps": [{"type": s["type"], "value": s.get("value", 0), "arg": s.get("arg", ""), "opt": s.get("opt", "")} for s in st]}
                         for i, t, st in routines], ensure_ascii=False)
        xml = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n    <string name=\"list\">%s</string>\n</map>\n" % escape(js, {'"': "&quot;"})
    sh("am", "force-stop", "--user", "10", PKG)
    adb("shell", f"run-as {PKG} --user 10 sh -c 'cat > shared_prefs/routines.xml'", inp=xml)

def open_routine_screen():
    sh("am", "start", "--user", "10", "-n", f"{PKG}/probe.RoutineActivity")
    for _ in range(10):
        time.sleep(1.5)
        if tap("루틴"): break
    time.sleep(1.5)

def S(type, value=0, arg="", opt=""): return dict(type=type, value=value, arg=arg, opt=opt)

# ---------------- 케이스 ----------------
# expect_state: (경로, 기대값)  / expect_log: [(줄 접두 '✓'|'✕', 라벨 부분문자열, 메시지 부분문자열 또는 None)]
def H(*path):
    def g(d):
        for p in path: d = d[p]
        return d
    return g
POWER, AUTO, AC, RECIRC, FAN = H("hvac", "power_on"), H("hvac", "auto_on"), H("hvac", "ac_on"), H("hvac", "recirc_on"), H("hvac", "fan_speed")
TEMP, SEATH, WHEEL, CLEANER = H("hvac", "driver", "temp_c"), H("hvac", "driver", "seat_temp"), H("steering_wheel_heat", "level"), H("hvac_air_cleaner", "activated")

CASES = []
def case(name, steps, state=(), log=(), end=True, slow=False, mid=None):
    CASES.append(dict(name=name, steps=steps, state=state, log=log, end=end, slow=slow, mid=mid))

case("A1_시원하게", [S("hvac_power", 1), S("hvac_auto", 0), S("hvac_ac", 1), S("hvac_recirc", 0), S("hvac_temp", 180), S("hvac_fan", 6)],
     state=[(POWER, True), (AUTO, False), (AC, True), (RECIRC, False), (TEMP, 18.0), (FAN, 6)])
case("A2_따뜻하게_열선", [S("hvac_power", 1), S("hvac_auto", 1), S("hvac_ac", 0), S("hvac_recirc", 1), S("hvac_temp", 265), S("hvac_fan", 2), S("seat_heat", 3), S("wheel_heat", 2)],
     state=[(AUTO, True), (AC, False), (RECIRC, True), (TEMP, 26.5), (FAN, 2), (SEATH, 3), (WHEEL, 2)])
case("A3_열선끄기_공기청정", [S("seat_heat", 0), S("wheel_heat", 0), S("air_cleaner", 0)], state=[(SEATH, 0), (WHEEL, 0), (CLEANER, False)])
case("A4_공기청정켜기_공조끄기", [S("air_cleaner", 1), S("hvac_power", 0)], state=[(CLEANER, True), (POWER, False)])
case("A5_공조다시켜기_최소값", [S("hvac_power", 1), S("hvac_temp", 150), S("hvac_fan", 1)], state=[(POWER, True), (TEMP, 15.0), (FAN, 1)])
case("A6_최대값", [S("hvac_temp", 300), S("hvac_fan", 8), S("seat_heat", 1), S("wheel_heat", 3)],
     state=[(TEMP, 30.0), (FAN, 8), (SEATH, 1), (WHEEL, 3)])
case("B1_조명콤보", [S("cabin_light", 1), S("reading_rear", 1), S("ambient", 50), S("ambient", 0), S("reading_rear", 0), S("cabin_light", 0)])
case("B2_시트등받이_알림", [S("notify", arg="테스트 알림"), S("seat_recline", 2), S("seat_recline", -2), S("seat_recline", 0)],
     log=[("✓", "운전석 등받이 눕히기 2초", "2초 이동 후 정지"), ("✓", "운전석 등받이 세우기 2초", None), ("✓", "그대로", None)])
case("C1_내비실패후에도계속", [S("navi", arg="", opt="SCHOOL_ZONE"), S("navi", arg="회사", opt="RECOMMENDED"), S("navi", arg="없는곳XYZ", opt="SHORT_TIME"), S("hvac_fan", 4)],
     state=[(FAN, 4)],
     log=[("✕", "내비 목적지", "목적지를 정해"), ("✕", "‘회사’", "비어 있습니다"), ("✕", "없는곳XYZ", "없습니다"), ("✓", "풍량 4단", None)])
case("C2_내비집_등록됨", [S("navi", arg="집", opt="RECOMMENDED")], log=[("✓", "‘집’", None)])   # 내비 앱에 집을 등록해 둔 상태에서만 통과
case("D1_빈루틴", [], log=[])
case("D2_긴조합_15단계", [S("hvac_power", 1), S("hvac_auto", 0), S("hvac_ac", 1), S("hvac_recirc", 1), S("hvac_temp", 215), S("hvac_fan", 5), S("seat_heat", 2), S("wheel_heat", 1),
                         S("cabin_light", 1), S("reading_rear", 1), S("ambient", 30), S("notify", arg="15단계 끝"), S("air_cleaner", 1), S("hvac_fan", 3), S("hvac_temp", 225)],
     state=[(POWER, True), (AUTO, False), (RECIRC, True), (TEMP, 22.5), (FAN, 3), (SEATH, 2), (WHEEL, 1), (CLEANER, True)])

def _gear_to_drive():
    put("/vehicle/gear?gear=Drive")
case("E1_주행중_시트차단", [S("notify", arg="1분 뒤 시트"), S("wait_min", 1), S("seat_recline", 3), S("hvac_fan", 7)],
     state=[(FAN, 7)], slow=True, mid=_gear_to_drive,
     log=[("✕", "운전석 등받이 눕히기 3초", "주행 중에는 시트를 움직이지 않습니다"), ("✓", "풍량 7단", None)])

def _tap_stop(): tap("중지")
case("E2_중간에중지", [S("hvac_fan", 4), S("wait_min", 1), S("hvac_fan", 8)], state=[(FAN, 4)], end=False, slow=True, mid=_tap_stop,
     log=[("✓", "풍량 4단", None)])

DEFAULTS_NOTE = "기본루틴(등하원·출근)"

# ---------------- 실행 ----------------
def parse_log(text):
    """화면 텍스트에서 ✓/✕ 줄 목록 [(mark, rest)]"""
    out = []
    for ln in text.splitlines():
        for l in ln.split("\n"):
            m = re.match(r"\s*([✓✕])\s+(.*)", l.strip())
            if m: out.append((m.group(1), m.group(2)))
    return out

def run_case(c, idx=0):
    put("/vehicle/gear?gear=Park"); time.sleep(1)
    write_routines([("t", c["name"], c["steps"])])
    open_routine_screen()
    if not tap("▶  실행"): return False, ["실행 버튼을 못 찾음: " + screen()[:200]]
    t0 = time.time(); txt = ""; mid_done = c["mid"] is None
    limit = 30 + sum(60 for s in c["steps"] if s["type"] == "wait_min") + sum(abs(s["value"]) for s in c["steps"] if s["type"] == "seat_recline")
    while time.time() - t0 < limit:
        time.sleep(2)
        txt = screen()
        if not mid_done and ("기다립니다" in txt):
            time.sleep(8); c["mid"](); mid_done = True
            if c["name"].startswith("E2"):
                time.sleep(2); txt = screen(); break
        if "■" in txt and mid_done: break
    time.sleep(1.5); txt = screen() if not c["name"].startswith("E2") else screen()
    fails = []
    lines = parse_log(txt)
    if c["end"] and f"■ ‘{c['name']}’ 끝" not in txt: fails.append("끝 표시(■ … 끝)가 없음")
    if not c["end"] and "중지했습니다" not in txt: fails.append("중지 표시가 없음")
    for mark, label, msg in c["log"]:
        if not any(m == mark and label in r and (msg is None or msg in r) for m, r in lines):
            fails.append(f"로그 기대 불일치: {mark} {label} {msg or ''}")
    expected_ok = None
    if not c["log"]:
        bad = [r for m, r in lines if m == "✕"]
        if bad: fails.append("예상 밖 실패 줄: " + "; ".join(bad))
    time.sleep(2)
    st = state()
    for g, want in c["state"]:
        got = g(st)
        if got != want: fails.append(f"상태 불일치: 기대 {want!r}, 실제 {got!r}")
    put("/vehicle/gear?gear=Park")
    return not fails, fails + (["[로그]\n" + "\n".join(f"{m} {r}" for m, r in lines)] if fails else [])

def tap_run_for(title):
    """제목 줄 바로 아래에 있는 ▶ 실행 버튼을 누른다."""
    ns = nodes()
    ys = [b[1] for t, b in ns if t == title]
    if not ys:   # 스크롤로 제목이 가려졌으면 맨 위에 보이는 버튼이 그 루틴의 것
        ys = [230]
    btns = sorted([b for t, b in ns if t.startswith("▶  실행") and b[1] >= ys[0]], key=lambda b: b[1])
    if not btns: return False
    b = btns[0]; sh("input", "tap", str((b[0] + b[2]) // 2), str((b[1] + b[3]) // 2)); return True

def run_defaults():
    """기본 루틴(등하원·출근)을 그대로 실행 — 내비 목적지가 비어 있으므로 내비만 ✕, 나머지는 ✓ 이어야 한다."""
    put("/vehicle/gear?gear=Park"); results = []
    for idx, (title, st_expect) in enumerate([("등하원", [(POWER, True), (TEMP, 22.5), (FAN, 3)]), ("출근", [(AUTO, True), (TEMP, 22.0), (SEATH, 2), (WHEEL, 1)])]):
        write_routines(None); open_routine_screen()
        ok_tap = tap_run_for(title) if any(t == title for t, _ in nodes()) else False
        if not ok_tap:
            sh("input", "swipe", "670", "430", "670", "250", "300"); time.sleep(1); ok_tap = tap_run_for(title)
        if not ok_tap: results.append((title, False, ["실행 버튼 없음"])); continue
        t0 = time.time()
        while time.time() - t0 < 60:
            time.sleep(2); txt = screen()
            if "■" in txt: break
        lines = parse_log(txt); fails = []
        bad = [r for m, r in lines if m == "✕"]
        if len(bad) != 1 or "내비" not in bad[0] and "‘회사’" not in bad[0]: fails.append("내비 외 실패가 있거나 내비 실패가 없음: " + str(bad))
        time.sleep(2); s = state()
        for g, want in st_expect:
            if g(s) != want: fails.append(f"상태 불일치: 기대 {want!r}, 실제 {g(s)!r}")
        results.append((f"기본루틴_{title}", not fails, fails))
    return results

def run_persist():
    """앱을 강제 종료했다 다시 열어도 만든 루틴이 남아 있는지."""
    write_routines([("p1", "내가만든루틴", [S("hvac_fan", 5)]), ("p2", "두번째루틴", [S("cabin_light", 1)])])
    open_routine_screen(); a = screen()
    sh("am", "force-stop", "--user", "10", PKG); open_routine_screen(); b = screen()
    ok = all(k in a and k in b for k in ["내가만든루틴", "두번째루틴"])
    return [("F1_저장유지_재시작", ok, [] if ok else ["재시작 후 루틴이 사라짐"])]

def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]; fast = "--fast" in sys.argv
    res = []
    for c in CASES:
        if args and not any(c["name"].startswith(a) for a in args): continue
        if fast and c["slow"]: continue
        t = time.time(); ok, why = run_case(c); res.append((c["name"], ok, why)); print(("PASS" if ok else "FAIL"), c["name"], f"{time.time()-t:.0f}s", flush=True)
        for w in why: print("   ", w, flush=True)
    if not args or "기본" in args or "F" in args:
        for r in run_defaults() + run_persist():
            res.append(r); print(("PASS" if r[1] else "FAIL"), r[0], flush=True)
            for w in r[2]: print("   ", w, flush=True)
    write_routines(None); put("/vehicle/gear?gear=Park")
    n = sum(1 for r in res if r[1]); print(f"\n== {n}/{len(res)} 통과 ==")
    json.dump([dict(name=r[0], ok=r[1], why=r[2]) for r in res], open(os.path.join(os.path.dirname(__file__), "routine_results.json"), "w"), ensure_ascii=False, indent=1)
    sys.exit(0 if n == len(res) else 1)

if __name__ == "__main__":
    main()
