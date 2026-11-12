# 원터치 루틴

차량 기능 여러 개를 이름 붙여 묶어 두고, 버튼 한 번으로 순서대로 실행하는 Pleos(현대차) 인카 앱.
예) '출근' = 내비(회사) → 공조 켜기 → 22℃ → 운전석 열선 2단 → 핸들 열선 1단

- 패키지 `probe.reversemap`, minSdk/target은 `app/build.gradle.kts` 참고
- 개발·시험 환경: Pleos Connect Emulator (AAOS 14). 실차에서는 확인하지 않았다.

## 코드 구조
| 파일 | 역할 |
|---|---|
| `app/src/main/java/probe/RoutineActivity.kt` | 화면(목록·편집·기능 추가), 실행 진행 표시, 이어서 실행 안내 |
| `app/src/main/java/probe/RoutineRunner.java` | 단계를 차례로 실행. 차량 API 호출 → 성공/실패 콜백 → 다음 단계 |
| `app/src/main/java/probe/Routines.kt` | 기능 목록(카탈로그), 기본 루틴, 저장(SharedPreferences JSON) |

기능을 추가하려면 `Routines.catalog`에 한 줄, `RoutineRunner.exec`에 case 한 줄.

## 동작 흐름
```
앱 실행 (RoutineActivity.onCreate)
 ├ 차량 SDK 연결 (실패해도 화면은 뜬다)
 ├ Routines.load        저장된 루틴 읽기, 없거나 깨졌으면 기본 루틴 3개
 ├ 대기 중 끊긴 루틴이 있으면 "이어서 실행할까요?" 확인 (너무 오래 지났으면 버림)
 └ 목록 화면 표시

[▶ 실행] 누름
 └ RoutineRunner.run(루틴)
     step 1 → 2 → 3 … 을 next(i) 재귀로 하나씩 실행
       차량 기능: 지원 영역 조회 → 값 설정 → 성공/실패 콜백 → 화면에 ✓/✕ 한 줄 → 다음 단계
       기다리기:  남은 단계와 끝날 시각을 저장하고 타이머로 대기 → 끝나면 다음 단계
       실패:      그 단계만 ✕ 로 표시하고 다음으로 넘어간다 (6초 안에 응답이 없으면 실패)
     마지막 단계 후 "끝" 표시
 [중지] 누르면 세대 번호(gen)를 올려, 예약돼 있던 콜백이 스스로 멈춘다

[편집] / [＋ 새 루틴]
 └ 제목 입력, 단계별 ＋/－ 로 값 조절, ▲▼ 순서 변경, ✕ 삭제, ＋ 기능 추가(분류별 목록)
   저장하면 Routines.save 가 JSON 으로 SharedPreferences 에 기록
```

## 기능 명세
| 기능 | 값 | 동작 | 필요 권한 |
|---|---|---|---|
| 내비 목적지 | 즐겨찾기 이름(집·회사는 분류로도 찾음), 경로 옵션(추천 / 스쿨존 우선 / 최소 시간) | 즐겨찾기에서 찾아 경로를 요청한다. 안내를 시작할지는 내비 앱이 정한다 | NAVI_ROUTE, NAVI_ROUTE_SEARCH |
| 공조 전원 | 켜기/끄기 | 공조 전원 설정 | CONTROL_CAR_CLIMATE |
| 온도 | 15.0~30.0℃, 0.5 단위 | 구역마다 따로 설정, 모두 응답하면 한 번에 보고 | 〃 |
| 풍량 | 1~8단 | 팬 속도 | 〃 |
| 오토 공조 / 에어컨 / 내기순환 / 공기청정 | 켜기/끄기 | 각각 설정 | 〃 |
| 운전석 열선 / 핸들 열선 | 0~3단 (0=끄기) | 시트는 시트 API, 핸들은 핸들 API로 설정 | 시트 CONTROL_CAR_SEATS, 핸들 CONTROL_CAR_CLIMATE (권한별 매핑은 에뮬레이터 로그 기준, 실차 미확인) |
| 실내등 | 켜기/끄기 | 실내 조명 | CONTROL_CAR_LIGHTS |
| 뒷좌석 독서등 | 켜기/끄기 | 2열 독서등만 | 〃 |
| 앰비언트 밝기 | 0~100% (0=끄기) | 0이면 끄기만, 아니면 켜고 밝기 설정 | 〃 |
| 운전석 등받이 | -10~+10초 (+눕힘 / -세움) | 위치 지정이 안 되어 N초 움직이다 멈춘다. **기어가 P가 아니면 실행하지 않고 실패 처리** | CONTROL_CAR_SEATS, CAR_DRIVE(기어 읽기) |
| 기다리기 | 1~60분 | 대기 중 앱이 꺼져도 이어서 실행 가능 | 없음 |
| 알림 보내기 | 문구 | 알림 표시 | POST_NOTIFICATIONS |

기본 루틴: **등하원**(스쿨존 내비, 실내등, 뒷좌석 독서등, 공조 22.5℃ 풍량 3), **출근**(회사 내비, 오토 공조 22℃, 운전석 열선 2단, 핸들 열선 1단), **휴식 10분**(공조·앰비언트·등받이 눕히기 → 10분 대기 → 원복 후 알림).

활용 예시(앱에 들어 있는 기본 루틴이 아니라, 지금 있는 기능만으로 사용자가 만들 수 있는 조합):
- **캠핑**: 공조 켜기 → 온도 24℃ → 풍량 2 → 내기순환 켜기 → 실내등 끄기 → 앰비언트 20% → 등받이 눕히기 6초 → 알림 "캠핑 모드를 켰습니다" (등받이는 P단에서만 움직인다)
- **세차**: 에어컨 끄기 → 내기순환 켜기 → 풍량 1 → 앰비언트 끄기 → 알림 "세차 모드: 외부 공기를 막았습니다" (세차 뒤 되돌리는 루틴을 따로 만들어 두면 된다)

## 권한 승인 절차
*확인 필요: 앱 마켓에서 권한이 설치 때 승인되는지, 실행 중 요청해야 하는지는 확인하지 못했다. 에뮬레이터에서는 권한이 실제로 검사된다. 현대 측 가이드로 확인해야 한다.
- 사용하는 권한은 공조, 시트, 조명, 내비, 기어 읽기, 알림으로 묶어 설명할 수 있다.
- 일부를 거부해도 앱은 열리고, 해당 단계만 "권한이 없습니다"로 실패 처리한다.


## 조합 항목별 SDK 호출
공통 순서는 `check…Capabilities`(지원 영역·범위 조회) → `set…`(값 설정) → 성공/실패 콜백이다. 영역(좌석 위치)은 코드에 박지 않고 조회 결과에서 고르며, 범위 밖 값은 조회된 min/max로 자른다.
호출은 모두 `RoutineRunner.exec`에 있다. 항목 정의는 `Routines.catalog`다.

| 항목 (type) | 조회 | 설정 | 저장값(value) |
|---|---|---|---|
| 공조 전원 `hvac_power` | `HVAC.checkHvacPowerCapabilities` | `HVAC.setHvacPower(area, on)` | 1=켜기 0=끄기 |
| 온도 `hvac_temp` | `HVAC.checkHvacTemperatureCapabilities` | `HVAC.setHvacTemperature(area, ℃, CELSIUS)` 영역마다 | 10배 정수 (22.5℃=225) |
| 풍량 `hvac_fan` | `HVAC.checkHvacFanSpeedCapabilities` | `HVAC.setHvacFanSpeed(area, n)` | 1~8 |
| 오토 공조 `hvac_auto` | `HVAC.checkHvacAutoCapabilities` | `HVAC.setHvacAuto` | 1/0 |
| 에어컨 `hvac_ac` | `HVAC.checkHvacAcCapabilities` | `HVAC.setHvacAc` | 1/0 |
| 내기순환 `hvac_recirc` | `HVAC.checkHvacRecirculationCapabilities` | `HVAC.setHvacRecirculation` | 1/0 |
| 공기청정 `air_cleaner` | `HVAC.checkHvacAirCleanerCapabilities` | `HVAC.setHvacAirCleaner` | 1/0 |
| 운전석 열선 `seat_heat` | `Seat.checkSeatTemperatureCapabilities` | `Seat.setHvacSeatTemperature(운전석 영역, 단계)` | 0~3 |
| 핸들 열선 `wheel_heat` | 없음 | `SteeringWheel.setHvacSteeringWheelHeat(단계)` | 0~3 |
| 실내등 `cabin_light` | 없음 | `Light.setCabinLightSwitch(ON/OFF)` | 1/0 |
| 뒷좌석 독서등 `reading_rear` | `Light.checkReadingLightSwitchCapabilities` | `Light.setReadingLightSwitch(2열 영역마다)` | 1/0 |
| 앰비언트 `ambient` | `Light.checkAmbientLightSwitchCapabilities` | `Light.setAmbientLightSwitch` → 0이 아니면 `setAmbientLightBrightness` | 0~100 (0=끄기) |
| 운전석 등받이 `seat_recline` | `Gear.getCurrentGearSelectionState`(P 확인), `Seat.checkSeatBackrestAngleCapabilities` | `Seat.moveSeatBackrestAngle(BACKWARD/FORWARD)` → N초 뒤 `STOP` | +눕힘 / -세움 (초) |
| 내비 목적지 `navi` | `NaviHelper.getBookmarkInfo` | `NaviHelper.requestRoute(RouteInfo, RouteOption)` | arg=즐겨찾기 이름, opt=`RECOMMENDED`/`SCHOOL_ZONE`/`SHORT_TIME` |
| 기다리기 `wait_min` | 없음 | 타이머(`Handler.postDelayed`) | 1~60 (분) |
| 알림 `notify` | 없음 | `NotificationManager.notify` | arg=문구 |

내비 목적지는 이름에 포함되는 즐겨찾기를 찾고, "집"·"회사"는 분류(HOME/OFFICE)로도 찾는다. 즐겨찾기가 없거나 좌표가 0이면 실패로 알린다.

## 루틴 저장 방식
- `Routines.save`가 루틴 목록 전체를 JSON 문자열 하나로 SharedPreferences(`routines`)의 `list` 키에 쓴다. 편집 화면에서 저장을 누를 때 한 번 기록한다.
- 루틴 하나: `{ "id", "title", "steps": [ { "type", "value", "arg", "opt" } ] }`. 새 루틴의 id는 저장 시각, 기존 루틴은 같은 id 자리를 덮어쓴다.
- `[기본으로 되돌리기]`는 `list` 키를 지워 기본 루틴 3개가 다시 나오게 한다.
- 불러올 때 JSON이 깨져 있으면 기본 루틴으로 대체하고, 카탈로그에 없는 type의 단계는 버린다.

## 시간 설정과 중지
- **시간 설정:** `기다리기` 단계의 value가 분 단위다(1~60). 편집 화면에서 ＋/－로 1분씩 바꾼다. 기다리는 동안은 차 호출 없이 타이머만 돌고, 화면 아래 줄에 "진행 중 n/m단계 · 남은 시간"이 1초마다 갱신된다.
- **단계 제한 시간:** 차량 호출은 6초 안에 응답이 없으면 실패로 보고 다음 단계로 넘어간다. 등받이는 움직이는 초만큼 더 준다.
- **중지:** 로그 영역의 `[중지]`를 누르면 `RoutineRunner.cancel()`이 세대 번호(`gen`)를 올리고 대기 저장값을 지운다. 대기 중이던 타이머와 아직 오지 않은 콜백은 번호가 낡았음을 보고 스스로 멈춘다. 이미 차로 보낸 명령은 되돌리지 않는다. 등받이를 움직이는 중이면 정지 명령은 그대로 나간다.
- **앱이 꺼졌을 때:** 기다리기에 들어갈 때 남은 단계와 끝날 시각을 `pending`, `pendingEnd`에 저장한다(`commit()`으로 즉시 기록). 앱을 다시 열면 확인 창에서 "이어 실행"을 누르면 남은 시간만 기다린 뒤 남은 단계를 실행한다. 끝날 시각에서 15분이 지났으면 이어 실행하지 않고 저장값을 버린다.

## 데이터
- 루틴 목록: SharedPreferences `routines` 의 `list` (JSON 배열: id, title, steps[type, value, arg, opt])
- 대기 중 상태: 같은 파일의 `pending`, `pendingEnd`
- 낮/밤 모드: `night`
- 카탈로그에 없는 기능 type 은 불러올 때 버린다(옛 데이터 대비)
- 서버로 보내는 데이터는 없다

## 안전 규칙
- 운전석 등받이는 기어가 P일 때만 움직인다(주행 중 시트 조작 차단).
- 단계마다 6초 안에 응답이 없으면 실패로 처리하고 다음 단계로 간다.

## 빌드
```
./gradlew assembleDebug
adb install -r --user 10 app/build/outputs/apk/debug/app-debug.apk
```

## 시험 (`tests/`)
에뮬레이터 콘솔(`adb forward tcp:8088 tcp:8088`)로 차량 상태를 읽어 기대값과 비교한다.
한 번에 하나씩 실행한다(에뮬레이터 메모리가 빠듯하다).
- `routine_test.py` 루틴 실행 · `routine_ui_test.py` 화면 조작 · `resume_test.py` 이어서 실행
- `record_demo.py` 시연 영상 녹화 → `demo/`

## 한계
- 실차 동작(등받이 움직임, 앰비언트, 좌석별 독서등, 내비 안내 시작)은 확인하지 못했다.
- 내비 경로는 요청만 보낸다. 안내 시작은 내비 앱이 한다.
- 외부 기온은 에뮬레이터에 값이 없어 날씨 기준 온도 설정은 아직 만들지 않았다.
