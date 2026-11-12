"""시연 영상 녹화: python3 tests/record_demo.py  → demo/demo_raw.mp4, demo/cues.txt (자막 시각)"""
import sys, time, subprocess, os
sys.path.insert(0, "tests")
import routine_test as T
from routine_test import *
ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
T.put("/vehicle/gear?gear=Park")
ROUTINES = [
    ("a", "출근", [S("hvac_power", 1), S("hvac_temp", 220), S("seat_heat", 2), S("wheel_heat", 1)]),
    ("b", "휴식 1분", [S("hvac_fan", 2), S("ambient", 20), S("notify", arg="휴식을 시작합니다"), S("wait_min", 1), S("ambient", 0), S("notify", arg="1분이 지났어요")]),
]
def pos(labels):
    out = {}
    for t, b in T.nodes():
        if t in labels: out.setdefault(t, []).append(((b[0] + b[2]) // 2, (b[1] + b[3]) // 2))
    return out
def xy(x, y): sh("input", "tap", str(x), str(y))
def fresh():
    write_routines(ROUTINES)
    sh("am", "force-stop", "--user", "10", T.PKG); sh("am", "start", "--user", "10", "-n", f"{T.PKG}/probe.RoutineActivity"); time.sleep(6)
# 1) 좌표를 미리 구한다 (녹화 전)
fresh(); L = pos({"▶  실행", "편집", "밤 모드", "낮 모드"})
xy(*L["편집"][0]); time.sleep(3); E = pos({"＋  기능 추가", "취소"})
xy(*E["＋  기능 추가"][0]); time.sleep(3); P = pos({"닫기"})
print(L, E, P, flush=True)
# 2) 녹화: 위 좌표를 바로 누른다
fresh(); T.put("/vehicle/gear?gear=Park")
rec = subprocess.Popen([ADB, "shell", "screenrecord", "--time-limit", "170", "--bit-rate", "5000000", "/sdcard/demo.mp4"])
t0 = time.time(); cues = []
def cue(text): cues.append((time.time() - t0, text)); print(f"{cues[-1][0]:5.1f}  {text}", flush=True)
time.sleep(3);  cue("루틴 목록: 이름과 하는 일이 한눈에")
time.sleep(3);  cue("‘출근’ 실행 — 버튼 한 번"); xy(*L["▶  실행"][0]); time.sleep(10)
cue("단계마다 성공/실패가 한 줄씩 나옵니다"); time.sleep(4)
cue("‘휴식 1분’ 실행 — 기다리기 단계 포함"); xy(*L["▶  실행"][1]); time.sleep(12)
cue("진행 단계와 남은 시간 표시"); time.sleep(55)
cue("끝나면 알림, 앰비언트를 원래대로"); time.sleep(8)
cue("편집: 값 조절·순서 바꾸기·삭제"); xy(*L["편집"][0]); time.sleep(5)
cue("기능 추가: 분류별로 고릅니다"); xy(*E["＋  기능 추가"][0]); time.sleep(5)
xy(*P["닫기"][0]); time.sleep(2); xy(*E["취소"][0]); time.sleep(3)
cue("밤 모드"); xy(*L["밤 모드"][0]); time.sleep(5)
xy(*L["낮 모드"][0]) if "낮 모드" in L else xy(812, 122); time.sleep(3)
rec.wait()
subprocess.run([ADB, "pull", "/sdcard/demo.mp4", "demo/demo_raw.mp4"], check=True)
open("demo/cues.txt", "w").write("\n".join(f"{a:.1f}\t{b}" for a, b in cues))
