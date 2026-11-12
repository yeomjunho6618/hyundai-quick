"""대기 중 앱 종료 → 다시 열기 → 이어 실행 시험. 사용: python3 tests/resume_test.py"""
import sys, time
sys.path.insert(0, "tests")
import routine_test as T
from routine_test import *

T.put("/vehicle/gear?gear=Park"); T.put("/vehicle/hvac?fan_speed=2") if False else None
steps = [S("hvac_fan", 2), S("wait_min", 1), S("hvac_fan", 7)]
write_routines([("t", "ResumeCase", steps)])
open_routine_screen()
assert tap("▶  실행"), "실행 버튼 없음"
time.sleep(12)
print("대기 중 풍량:", FAN(state()))
adb("shell", "am", "force-stop", "--user", "10", T.PKG)       # 대기 중 앱 종료
print("앱 종료. 70초 대기")
time.sleep(70)
print("종료 후 풍량:", FAN(state()), "(7이면 이어지지 않았음이 아님: 2여야 정상)")
sh("am", "start", "--user", "10", "-n", f"{T.PKG}/probe.RoutineActivity"); time.sleep(3)
for _ in range(10):
    if tap("루틴"): break
    time.sleep(1.5)
time.sleep(3)
txt = screen()
print("재시작 화면에 묻는 창:", "이어서 실행할까요" in txt)
if tap("이어 실행"):
    time.sleep(12)
    print("이어 실행 후 풍량:", FAN(state()))
    print("로그:", "■ ‘ResumeCase’ 끝" in screen())
else:
    print("이어 실행 버튼 못 찾음:", txt[:200])
