"""루틴 화면 조작 테스트: 목록·편집 화면을 실제로 눌러서 루틴을 만들고 고치고 지우고 실행한다.
routine_test.py 의 화면 읽기/누르기 도우미를 재사용. 사용: python3 tests/routine_ui_test.py [케이스 접두사 ...]
(한글 입력은 adb 가 못 하므로 이름·알림 문구는 영문으로 입력한다.)"""
import json, os, sys, time
sys.path.insert(0, os.path.dirname(__file__))
import routine_test as T
from routine_test import nodes, screen, sh, put, state, write_routines, open_routine_screen, POWER, TEMP, FAN, SEATH, AUTO, WHEEL

def tap_node(pred, last=False):
    hits = [b for t, b in nodes() if pred(t)]
    if not hits: return False
    b = hits[-1] if last else hits[0]; sh("input", "tap", str((b[0] + b[2]) // 2), str((b[1] + b[3]) // 2)); return True

def swipe(x=670, y1=440, y2=250): sh("input", "swipe", str(x), str(y1), str(x), str(y2), "300"); time.sleep(0.8)
def swipe_down(x=670): swipe(x, 300, 470)

def tap_scroll(label, exact=True, last=False, tries=5, area=(670, 440, 250)):
    """보이지 않으면 위로 밀어 가며 찾는다. 다 찾아도 없으면 맨 위로 되돌려 한 번 더."""
    pred = (lambda t: t == label) if exact else (lambda t: t.startswith(label))
    for _ in range(tries):
        if tap_node(pred, last): return True
        swipe(area[0], area[1], area[2])
    for _ in range(tries): swipe_down(area[0])
    return tap_node(pred, last)

def go_top():
    for _ in range(5): swipe_down()

# ---- 목록 화면 ----
def fresh_list(routines=None):
    write_routines(routines); open_routine_screen(); go_top()

def list_titles_text(): return screen()

# ---- 편집 화면 ----
def editor_rows():
    """편집 화면의 단계 줄: [(번호.설명, (좌,상,우,하))]"""
    import re
    return [(t, b) for t, b in nodes() if re.match(r"^\d+\. ", t)]

def row_button(prefix, label):
    """'N. 설명' 으로 시작하는 줄과 같은 높이에 있는 버튼(＋ － ▲ ▼ ✕)을 누른다."""
    for _ in range(6):
        rows = [(t, b) for t, b in editor_rows() if t.startswith(prefix)]
        if rows:
            ry = (rows[0][1][1] + rows[0][1][3]) // 2
            ns = nodes()
            btn = [b for t, b in ns if t == label and abs((b[1] + b[3]) // 2 - ry) < 30]
            if btn:
                b = btn[0]; sh("input", "tap", str((b[0] + b[2]) // 2), str((b[1] + b[3]) // 2)); time.sleep(0.7); return True
        swipe(670, 450, 330)
    return False

def row_text_tap(prefix):
    for _ in range(6):
        rows = [(t, b) for t, b in editor_rows() if t.startswith(prefix)]
        if rows:
            b = rows[0][1]; sh("input", "tap", str(b[0] + 40), str((b[1] + b[3]) // 2)); time.sleep(1); return True
        swipe(670, 450, 330)
    return False

def add_feature(label):
    if not tap_node(lambda t: t == "＋  기능 추가"): return False
    time.sleep(1)
    for _ in range(6):
        if tap_node(lambda t: t == label, last=True): time.sleep(0.8); return True
        swipe(670, 500, 260)
    return False

def type_title(text, clear=False):
    """제목 입력창은 ‘새 루틴/루틴 편집’ 머리글 바로 아래에 있다."""
    hdr = [b for t, b in nodes() if t in ("새 루틴", "루틴 편집")]
    y = (hdr[0][3] + 28) if hdr else 235
    sh("input", "tap", "670", str(y)); time.sleep(0.5)
    if clear:
        sh("input", "keyevent", "KEYCODE_MOVE_END")
        for _ in range(30): sh("input", "keyevent", "KEYCODE_DEL")
    sh("input", "text", text); time.sleep(0.5)
    sh("input", "keyevent", "KEYCODE_BACK"); time.sleep(0.8)      # 입력창 키보드를 닫아야 이후 탭이 먹는다

def dialog_text(text, ok="확인"):
    time.sleep(0.5); sh("input", "text", text); time.sleep(0.4)
    sh("input", "keyevent", "KEYCODE_BACK"); time.sleep(0.8)      # 키보드를 먼저 닫아야 확인 탭이 먹는다
    for _ in range(3):                                            # 키보드가 첫 탭을 먹을 수 있어 대화상자가 닫힐 때까지 반복
        tap_node(lambda t: t == ok, last=True); time.sleep(1)
        if not any(t in ("내비 목적지", "알림 문구") for t, _ in nodes()): return True
    return False

def tap_leave_editor(label):
    """제목 입력 직후 첫 탭은 입력 포커스에 먹히는 일이 있어, 편집 화면을 벗어날 때까지 최대 3번 누른다."""
    for _ in range(3):
        tap_node(lambda t: t == label); time.sleep(1.5)
        if not on_editor(): return True
    return False

def on_editor(): return any(t in ("새 루틴", "루틴 편집") for t, _ in nodes())

def run_routine_by_title(title):
    """목록을 스크롤해 title 바로 아래의 ▶ 실행을 누르고, 끝까지 기다려 화면 텍스트를 돌려준다."""
    go_top()
    for _ in range(6):
        ns = nodes(); ys = [b[1] for t, b in ns if t == title]
        btns = sorted([b for t, b in ns if t.startswith("▶  실행") and ys and b[1] >= ys[0]], key=lambda b: b[1])
        if btns:
            b = btns[0]; sh("input", "tap", str((b[0] + b[2]) // 2), str((b[1] + b[3]) // 2)); break
        swipe(670, 440, 330)
    else: return None
    t0 = time.time(); txt = ""
    while time.time() - t0 < 60:
        time.sleep(2); txt = screen()
        if "■" in txt: break
    return txt

# ---------------- 케이스 ----------------
def u1_create():
    """새 루틴: 이름 입력 → 기능 7개 추가 → ＋/－ 로 값 조절 → 텍스트 입력 → ▲로 순서 바꾸기 → ✕로 삭제 → 저장 → 실행."""
    f = []
    fresh_list(None)
    if not tap_scroll("＋  새 루틴 만들기"): return ["새 루틴 버튼 없음"]
    time.sleep(1.5)
    if not on_editor(): return ["편집 화면이 안 열림"]
    type_title("UiTest")
    for lab in ["공조 전원", "온도", "풍량", "운전석 열선", "알림 보내기", "내비 목적지", "앰비언트 밝기"]:
        if not add_feature(lab): f.append("기능 추가 실패: " + lab)
    # 값 조절
    for _ in range(4): row_button("2. 온도", "＋")          # 22.0 → 24.0
    for _ in range(2): row_button("3. 풍량", "＋")          # 3 → 5
    row_button("4. 운전석 열선", "＋")                       # 2 → 3
    row_button("6. 내비 목적지", "＋")                       # 추천 → 스쿨존 우선
    # 텍스트 입력: 알림 문구, 내비 목적지('집' 버튼)
    if row_text_tap("5. 알림"):
        time.sleep(0.5)
        tap_node(lambda t: t == "알림")                         # 입력창(기본 문구 ‘알림’)에 포커스
        sh("input", "keyevent", "KEYCODE_MOVE_END")
        for _ in range(10): sh("input", "keyevent", "KEYCODE_DEL")
        dialog_text("hello")
    else: f.append("알림 문구 줄을 못 누름")
    if row_text_tap("6. 내비 목적지"):
        time.sleep(0.5)
        if not tap_node(lambda t: t == "집", last=True): f.append("내비 ‘집’ 버튼 없음")
    else: f.append("내비 줄을 못 누름")
    time.sleep(0.8)
    # 순서 바꾸기: 알림 줄이 1번이 될 때까지 ▲
    for _ in range(8):
        r = [t for t, _b in editor_rows() if "알림" in t]
        if r and r[0].startswith("1. "): break
        if r: row_button(r[0].split(" ")[0] + " 알림", "▲")
        else: swipe_down()
    # 삭제: 앰비언트(7번)
    if not row_button("7. 앰비언트", "✕"): f.append("앰비언트 삭제 실패")
    import sys as _s; print("  [편집 저장 직전]", [t[:14] for t, _ in nodes()], file=_s.stderr)
    go_top()
    # 편집 화면 상태 검증 (스크롤 전체를 보려고 여러 번 읽음)
    seen = set(t for t, _ in editor_rows())
    for _ in range(4):
        swipe(670, 450, 300); seen |= set(t for t, _ in editor_rows())
    want = ["1. 알림 ‘hello’", "2. 공조 전원 켜기", "3. 온도 24.0℃", "4. 풍량 5단", "5. 운전석 열선 3단", "6. 내비 목적지 ‘집’ · 스쿨존 우선"]
    for w in want:
        if w not in seen: f.append(f"편집 화면 기대 불일치: {w!r} (실제 {sorted(seen)})")
    if any("앰비언트" in s for s in seen): f.append("삭제한 앰비언트 줄이 남아 있음")
    # 저장
    if not tap_leave_editor("저장"): f.append("저장 후에도 편집 화면")
    out = run_routine_by_title("UiTest")
    if out is None: return f + ["목록에 UiTest 루틴이 없음"]
    time.sleep(2); st = state()
    for name, g, w in [("전원", POWER, True), ("온도", TEMP, 24.0), ("풍량", FAN, 5), ("열선", SEATH, 3)]:
        if g(st) != w: f.append(f"실행 후 상태 불일치 {name}: 기대 {w!r}, 실제 {g(st)!r}")
    # 내비에 ‘집’이 등록돼 있으면 경로 요청이 성공하며 내비 앱이 앞으로 나와 결과 줄을 못 읽는다. 그 경우 내비가 앞에 있으면 통과.
    navi_front = "ai.umos.maps.android.navigation.app" in sh("dumpsys", "window") .split("mCurrentFocus")[-1][:200]
    if "‘집’" not in out and not navi_front: f.append("내비 줄 결과가 없음")
    # 재시작 후 유지
    sh("am", "force-stop", "--user", "10", T.PKG)
    saved = sh("run-as", T.PKG, "--user", "10", "cat", "shared_prefs/routines.xml")
    if "UiTest" not in saved: f.append("저장소에 UiTest 루틴이 없음")
    open_routine_screen()
    if run_routine_by_title("UiTest") is None: f.append("재시작 후 목록에 UiTest 루틴이 없음")
    return f

def u2_validation():
    f = []
    fresh_list(None); n0 = screen()
    if not tap_scroll("＋  새 루틴 만들기"): return ["새 루틴 버튼 없음"]
    time.sleep(1.5)
    tap_node(lambda t: t == "저장"); time.sleep(1)               # 이름·기능 모두 없음
    if not any("이름을 적어" in t for t, _ in nodes()): f.append("이름 없이 저장했는데 안내창이 없음")
    tap_node(lambda t: t == "확인"); time.sleep(1)
    if not on_editor(): f.append("이름 없이 저장했는데 편집 화면을 벗어남")
    type_title("OnlyName"); tap_node(lambda t: t == "저장"); time.sleep(1)   # 기능 없음
    if not any("하나 이상" in t for t, _ in nodes()): f.append("기능 없이 저장했는데 안내창이 없음")
    tap_node(lambda t: t == "확인"); time.sleep(1)
    if not on_editor(): f.append("기능 없이 저장했는데 편집 화면을 벗어남")
    tap_leave_editor("취소")                                       # 취소 → 목록, 저장 안 됨
    if on_editor(): f.append("취소 후에도 편집 화면")
    sh("am", "force-stop", "--user", "10", T.PKG); open_routine_screen()
    if "OnlyName" in screen(): f.append("취소한 루틴이 저장됨")
    return f

def u3_edit_existing():
    f = []
    fresh_list(None)
    go_top(); ok = False
    for _ in range(8):                                             # 제목 바로 아래의 잘리지 않은 편집 버튼을 찾을 때까지 조금씩 민다
        ns = nodes(); ys = [b[1] for t, b in ns if t == "출근"]
        eds = sorted([b for t, b in ns if t == "편집" and ys and b[1] >= ys[0] and b[3] - b[1] >= 40 and b[3] <= 455], key=lambda b: b[1])
        if eds:
            b = eds[0]; sh("input", "tap", str((b[0] + b[2]) // 2), str((b[1] + b[3]) // 2)); ok = True; break
        swipe(670, 440, 390)
    if not ok: return ["출근 편집 버튼을 못 찾음"]
    time.sleep(1.5)
    if not on_editor(): return ["편집 화면이 안 열림"]
    type_title("Renamed", clear=True)
    # 열선 단계를 찾아 한 단계 낮춤(2→1), 온도 +1.0(22.0→24.0 아님: 두 번)
    row_button("3. 오토 공조", "－") or f.append("오토 공조 －")          # 켜기 → 끄기
    for _ in range(2): row_button("4. 온도", "＋")                       # 22.0 → 23.0
    row_button("5. 운전석 열선", "－")                                  # 2 → 1
    tap_leave_editor("저장")
    go_top(); s = screen()
    if "Renamed" not in s: f.append("이름 변경이 목록에 없음")
    if "출근" in s.split("\n") : f.append("이전 이름이 남아 있음")
    out = run_routine_by_title("Renamed")
    if out is None: return f + ["Renamed 루틴을 못 찾음"]
    time.sleep(2); st = state()
    for name, g, w in [("오토", AUTO, False), ("온도", TEMP, 23.0), ("열선", SEATH, 1), ("핸들열선", WHEEL, 1)]:
        if g(st) != w: f.append(f"실행 후 상태 불일치 {name}: 기대 {w!r}, 실제 {g(st)!r}")
    return f

def u4_delete():
    f = []
    fresh_list(None); go_top()
    ns = nodes(); dels = sorted([b for t, b in ns if t == "삭제"], key=lambda b: b[1])
    b = dels[0]; sh("input", "tap", str((b[0] + b[2]) // 2), str((b[1] + b[3]) // 2)); time.sleep(1)
    if "삭제할까요" not in screen(): return ["삭제 확인창이 안 뜸"]
    tap_node(lambda t: t == "취소", last=True); time.sleep(1)       # 취소 → 그대로
    if "등하원" not in screen(): f.append("취소했는데 루틴이 사라짐")
    b = sorted([b for t, b in nodes() if t == "삭제"], key=lambda b: b[1])[0]; sh("input", "tap", str((b[0] + b[2]) // 2), str((b[1] + b[3]) // 2)); time.sleep(1)
    tap_node(lambda t: t == "삭제", last=True); time.sleep(1.5)     # 확인
    go_top(); s = screen().split("\n")
    if "등하원" in s: f.append("삭제 후에도 등하원이 남아 있음")
    if "출근" not in s: f.append("다른 루틴(출근)까지 사라짐")
    sh("am", "force-stop", "--user", "10", T.PKG); open_routine_screen(); s2 = screen().split("\n")
    if "등하원" in s2: f.append("재시작 후 삭제한 루틴이 되살아남")
    return f

def u5_reset():
    f = []
    fresh_list([("x1", "Only", [T.S("hvac_fan", 2)])])
    if "Only" not in screen(): return ["준비 루틴이 안 보임"]
    tap_node(lambda t: t == "기본으로 되돌리기"); time.sleep(1)
    if "기본 루틴 3개" not in screen(): return ["되돌리기 확인창이 안 뜸"]
    tap_node(lambda t: t == "취소", last=True); time.sleep(1)       # 취소 → 그대로
    if "Only" not in screen(): f.append("취소했는데 루틴이 바뀜")
    tap_node(lambda t: t == "기본으로 되돌리기"); time.sleep(1); tap_node(lambda t: t == "되돌리기", last=True); time.sleep(1.5)
    go_top(); s = screen().split("\n")
    if "Only" in s: f.append("되돌린 뒤에도 Only 가 남음")
    if "등하원" not in s: f.append("기본 루틴(등하원)이 없음")
    return f

CASES = [("U1_만들기_조절_순서_삭제_저장_실행", u1_create), ("U2_검증_취소", u2_validation), ("U3_기존편집", u3_edit_existing), ("U4_삭제", u4_delete), ("U5_기본으로되돌리기", u5_reset)]

def main():
    args = sys.argv[1:]; res = []
    put("/vehicle/gear?gear=Park")
    for name, fn in CASES:
        if args and not any(name.startswith(a) for a in args): continue
        t = time.time()
        try: why = fn()
        except Exception as e: why = [f"예외: {e!r}"]
        res.append(dict(name=name, ok=not why, why=why)); print("PASS" if not why else "FAIL", name, f"{time.time()-t:.0f}s", flush=True)
        for w in why: print("   ", w, flush=True)
    write_routines(None)
    n = sum(r["ok"] for r in res); print(f"\n== {n}/{len(res)} 통과 ==")
    json.dump(res, open(os.path.join(os.path.dirname(__file__), "routine_ui_results.json"), "w"), ensure_ascii=False, indent=1)
    sys.exit(0 if n == len(res) else 1)

if __name__ == "__main__":
    main()
