package probe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import ai.pleos.playground.navi.constants.BookmarkType;
import ai.pleos.playground.navi.constants.RouteOption;
import ai.pleos.playground.navi.data.BookmarkInfo;
import ai.pleos.playground.navi.data.RouteInfo;
import ai.pleos.playground.navi.helper.NaviHelper;
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener;
import ai.pleos.playground.vehicle.Vehicle;
import ai.pleos.playground.vehicle.api.model.area.SeatArea;
import ai.pleos.playground.vehicle.constant.area.SeatValue;
import ai.pleos.playground.vehicle.constant.control.LightSwitch;
import ai.pleos.playground.vehicle.constant.control.MoveControl;
import ai.pleos.playground.vehicle.constant.control.VehicleGear;
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit;
import kotlin.Unit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 루틴 단계를 차례로 차량 API에 보낸다. 실패해도 다음 단계로 간다. */
public final class RoutineRunner {
    public interface Out { void line(String s); void finished(boolean cancelled); default void begin(int index, Step s) {} }
    private interface Done { void ok(String msg); void fail(String msg); }

    private static final Handler H = new Handler(Looper.getMainLooper());
    private static Context app;
    private static Vehicle vehicle;
    private static NaviHelper navi;
    private static boolean naviReady;
    // 중지/재실행 때 올림. 예약된 콜백은 번호가 다르면 멈춤
    private static int gen = 0;

    private RoutineRunner() {}

    public static synchronized void init(Context c) {
        if (vehicle != null) return;
        app = c.getApplicationContext();
        vehicle = new Vehicle(app);
        vehicle.initialize();
    }

    public static boolean running() { return running; }
    private static boolean running;

    public static void cancel() { gen++; running = false; if (app != null) Routines.INSTANCE.clearPending(app); }

    private static String curTitle = "";
    public static void run(final List<Step> steps, final Out out) { run("", steps, out); }
    public static void run(final String title, final List<Step> steps, final Out out) {
        final int g = ++gen; running = true; curTitle = title;
        next(g, steps, 0, out);
    }

    /** 앱이 꺼져서 끊긴 대기를 이어간다. 남은 시간만 기다리고 나머지 단계 실행 */
    public static void resume(final Routines.Pending p, final Out out) {
        final int g = ++gen; running = true; curTitle = p.getTitle();
        long left = Math.max(0L, p.getEndMs() - System.currentTimeMillis());
        out.line("… 남은 " + (left / 60000) + "분 " + (left / 1000 % 60) + "초를 기다립니다");
        H.postDelayed(() -> { if (g == gen) { out.line("✓ 기다림이 끝났습니다"); next(g, p.getRest(), 0, out); } else out.finished(true); }, left);
    }

    // 한 단계 하고 next(i+1)
    private static void next(final int g, final List<Step> steps, final int i, final Out out) {
        if (g != gen) { out.finished(true); return; }
        if (i >= steps.size()) { running = false; if (app != null) Routines.INSTANCE.clearPending(app); out.finished(false); return; }
        final Step s = steps.get(i);
        final String label = Routines.INSTANCE.describe(s);
        out.begin(i, s);
        // 대기는 타이머만. 앱 죽을 때 대비해 남은 단계 저장
        if (s.getType().equals("wait_min")) {
            out.line("… " + s.getValue() + "분 기다립니다 (중지하려면 ‘중지’)");
            if (app != null) Routines.INSTANCE.savePending(app, curTitle, new ArrayList<>(steps.subList(i + 1, steps.size())), System.currentTimeMillis() + s.getValue() * 60_000L);
            H.postDelayed(() -> { if (g == gen) { out.line("✓ " + s.getValue() + "분이 지났습니다"); next(g, steps, i + 1, out); } else out.finished(true); }, s.getValue() * 60_000L);
            return;
        }
        // 응답이랑 타임아웃 둘 다 올 수 있어서 한 번만
        final boolean[] fin = {false};
        final Done d = new Done() {
            void end(String mark, String m) {
                if (fin[0]) return; fin[0] = true;
                H.post(() -> { out.line(mark + " " + label + (m == null || m.isEmpty() ? "" : " — " + m)); next(g, steps, i + 1, out); });
            }
            public void ok(String m) { end("✓", m); }
            public void fail(String m) { end("✕", m); }
        };
        // 6초 지나면 실패 처리. 등받이는 움직이는 시간 더함
        H.postDelayed(() -> d.fail("차가 응답하지 않았습니다"), 6000L + (s.getType().equals("seat_recline") ? Math.abs(s.getValue()) * 1000L : 0L));
        try { exec(s, d); } catch (Throwable t) { android.util.Log.i("ROUTINE", "exec " + s.getType(), t); d.fail(msg(t)); }
    }

    private static String nz(String x) { return x == null ? "" : x; }

    private static String msg(Object e) {
        String m = String.valueOf(e);
        if (m.contains("not supported") || m.contains("Invalid")) return "이 차는 지원하지 않습니다";
        if (m.contains("SecurityException") || m.contains("Permission")) return "권한이 없습니다";
        return m.length() > 80 ? m.substring(0, 80) : m;
    }

    private static boolean has(SeatArea a, SeatValue v) { for (SeatValue x : a.getValues()) if (x == v) return true; return false; }
    // 좌석은 SDK가 알려준 영역에서 고름. 1열 왼쪽 = 운전석
    private static SeatArea driverOrFirst(Iterable<SeatArea> it) {
        SeatArea first = null;
        for (SeatArea a : it) { if (first == null) first = a; if (has(a, SeatValue.ROW_1_LEFT)) return a; }
        return first;
    }

    // 공조/시트/조명 공통: 영역 조회 -> 값 설정 -> ok/fail. 범위 밖이면 min/max 로 자름
    private static void exec(final Step s, final Done d) {
        final Vehicle v = vehicle; final int val = s.getValue(); final boolean on = val == 1;
        switch (s.getType()) {
            case "hvac_power": v.getHVAC().checkHvacPowerCapabilities(a -> { if (a.isEmpty()) d.fail("지원 안 함"); else v.getHVAC().setHvacPower(a.get(0), on, (x, y) -> { d.ok(""); return Unit.INSTANCE; }, e -> { d.fail(msg(e)); return Unit.INSTANCE; }); return Unit.INSTANCE; }, e -> { d.fail(msg(e)); return Unit.INSTANCE; }); break;
            case "hvac_ac": v.getHVAC().checkHvacAcCapabilities(a -> { if (a.isEmpty()) d.fail("지원 안 함"); else v.getHVAC().setHvacAc(a.get(0), on, (x, y) -> { d.ok(""); return Unit.INSTANCE; }, e -> { d.fail(msg(e)); return Unit.INSTANCE; }); return Unit.INSTANCE; }, e -> { d.fail(msg(e)); return Unit.INSTANCE; }); break;
            case "hvac_auto": v.getHVAC().checkHvacAutoCapabilities(a -> { if (a.isEmpty()) d.fail("지원 안 함"); else v.getHVAC().setHvacAuto(a.get(0), on, (x, y) -> { d.ok(""); return Unit.INSTANCE; }, e -> { d.fail(msg(e)); return Unit.INSTANCE; }); return Unit.INSTANCE; }, e -> { d.fail(msg(e)); return Unit.INSTANCE; }); break;
            case "hvac_recirc": v.getHVAC().checkHvacRecirculationCapabilities(a -> { if (a.isEmpty()) d.fail("지원 안 함"); else v.getHVAC().setHvacRecirculation(a.get(0), on, (x, y) -> { d.ok(""); return Unit.INSTANCE; }, e -> { d.fail(msg(e)); return Unit.INSTANCE; }); return Unit.INSTANCE; }, e -> { d.fail(msg(e)); return Unit.INSTANCE; }); break;
            case "air_cleaner": v.getHVAC().checkHvacAirCleanerCapabilities(a -> { if (a.isEmpty()) d.fail("지원 안 함"); else v.getHVAC().setHvacAirCleaner(a.get(0), on, (x, y) -> { d.ok(""); return Unit.INSTANCE; }, e -> { d.fail(msg(e)); return Unit.INSTANCE; }); return Unit.INSTANCE; }, e -> { d.fail(msg(e)); return Unit.INSTANCE; }); break;
            case "hvac_fan": v.getHVAC().checkHvacFanSpeedCapabilities(m -> {
                    if (m.isEmpty()) { d.fail("지원 안 함"); return Unit.INSTANCE; }
                    SeatArea k = m.keySet().iterator().next(); int lo = m.get(k).getMinValue(), hi = m.get(k).getMaxValue();
                    v.getHVAC().setHvacFanSpeed(k, Math.max(lo, Math.min(hi, val)), (x, y) -> { d.ok(""); return Unit.INSTANCE; }, e -> { d.fail(msg(e)); return Unit.INSTANCE; });
                    return Unit.INSTANCE; }, e -> { d.fail(msg(e)); return Unit.INSTANCE; }); break;
            // 저장값 x10 이라 /10. 구역마다 보내고 다 오면 보고
            case "hvac_temp": v.getHVAC().checkHvacTemperatureCapabilities(m -> {
                    if (m.isEmpty()) { d.fail("지원 안 함"); return Unit.INSTANCE; }
                    final int[] left = {m.size()}; final boolean[] bad = {false};
                    for (Map.Entry<SeatArea, ai.pleos.playground.vehicle.api.model.capability.AreaBounds<Float>> en : m.entrySet()) {
                        float t = Math.max(en.getValue().getMinValue(), Math.min(en.getValue().getMaxValue(), val / 10f));
                        v.getHVAC().setHvacTemperature(en.getKey(), t, TemperatureUnit.CELSIUS,
                            (x, y) -> { if (--left[0] == 0) { if (bad[0]) d.fail("일부 좌석만 적용"); else d.ok(""); } return Unit.INSTANCE; },
                            e -> { bad[0] = true; if (--left[0] == 0) d.fail(msg(e)); return Unit.INSTANCE; });
                    }
                    return Unit.INSTANCE; }, e -> { d.fail(msg(e)); return Unit.INSTANCE; }); break;
            case "seat_heat": v.getSeat().checkSeatTemperatureCapabilities(m -> {
                    if (m.isEmpty()) { d.fail("지원 안 함"); return Unit.INSTANCE; }
                    v.getSeat().setHvacSeatTemperature(driverOrFirst(m.keySet()), val, (x, y) -> { d.ok(""); return Unit.INSTANCE; }, e -> { d.fail(msg(e)); return Unit.INSTANCE; });
                    return Unit.INSTANCE; }, e -> { d.fail(msg(e)); return Unit.INSTANCE; }); break;
            case "wheel_heat": v.getSteeringWheel().setHvacSteeringWheelHeat(val, x -> { d.ok(""); return Unit.INSTANCE; }, e -> { d.fail(msg(e)); return Unit.INSTANCE; }); break;
            case "cabin_light": v.getLight().setCabinLightSwitch(on ? LightSwitch.ON : LightSwitch.OFF, x -> { d.ok(""); return Unit.INSTANCE; }, e -> { d.fail(msg(e)); return Unit.INSTANCE; }); break;
            // 2열만. 영역이 여러 개라 left 로 센다
            case "reading_rear": v.getLight().checkReadingLightSwitchCapabilities(m -> {
                    final List<SeatArea> rear = new ArrayList<>();
                    for (SeatArea a : m.keySet()) if (has(a, SeatValue.ROW_2_LEFT) || has(a, SeatValue.ROW_2_CENTER) || has(a, SeatValue.ROW_2_RIGHT)) rear.add(a);
                    if (rear.isEmpty()) { d.fail("뒷좌석 독서등이 없습니다"); return Unit.INSTANCE; }
                    final int[] left = {rear.size()};
                    for (SeatArea a : rear) v.getLight().setReadingLightSwitch(a, on ? LightSwitch.ON : LightSwitch.OFF,
                        x -> { if (--left[0] == 0) d.ok(""); return Unit.INSTANCE; }, e -> { if (--left[0] == 0) d.fail(msg(e)); return Unit.INSTANCE; });
                    return Unit.INSTANCE; }, e -> { d.fail(msg(e)); return Unit.INSTANCE; }); break;
            // 0이면 끄기만, 아니면 켜고 밝기
            case "ambient": v.getLight().checkAmbientLightSwitchCapabilities((c, m) -> {
                    if (m.isEmpty()) { d.fail("지원 안 함"); return Unit.INSTANCE; }
                    final SeatArea k = m.keySet().iterator().next();
                    v.getLight().setAmbientLightSwitch(k, val == 0 ? LightSwitch.OFF : LightSwitch.ON, (x, y) -> {
                        if (val == 0) { d.ok(""); return Unit.INSTANCE; }
                        v.getLight().setAmbientLightBrightness(k, val, (x2, y2) -> { d.ok(""); return Unit.INSTANCE; }, e -> { d.fail("밝기: " + msg(e)); return Unit.INSTANCE; });
                        return Unit.INSTANCE; }, e -> { d.fail(msg(e)); return Unit.INSTANCE; });
                    return Unit.INSTANCE; }, e -> { d.fail(msg(e)); return Unit.INSTANCE; }); break;
            case "seat_recline": recline(s, d); break;
            case "notify": notifyUser(s.getArg()); d.ok(""); break;
            case "navi": navi(s, d); break;
            default: d.fail("알 수 없는 기능");
        }
    }

    /** 시트는 위치 지정이 안 되고 이동/정지만 있어서 N초 움직이다 멈춘다. 주행 중엔 안 움직임 */
    private static void recline(final Step s, final Done d) {
        final Vehicle v = vehicle;
        if (s.getValue() == 0) { d.ok("그대로"); return; }
        v.getGear().getCurrentGearSelectionState(gear -> {
            // 주행 중 시트 이동 막기 (P 아니면 실패)
            if (gear != VehicleGear.GEAR_PARK) { d.fail("주행 중에는 시트를 움직이지 않습니다 (P단에서만)"); return Unit.INSTANCE; }
            v.getSeat().checkSeatBackrestAngleCapabilities(m -> {
                if (m.isEmpty()) { d.fail("지원 안 함"); return Unit.INSTANCE; }
                final SeatArea k = driverOrFirst(m.keySet());
                v.getSeat().moveSeatBackrestAngle(k, s.getValue() > 0 ? MoveControl.BACKWARD : MoveControl.FORWARD, (x, y) -> {
                    H.postDelayed(() -> v.getSeat().moveSeatBackrestAngle(k, MoveControl.STOP, (x2, y2) -> { d.ok(Math.abs(s.getValue()) + "초 이동 후 정지"); return Unit.INSTANCE; }, e -> { d.fail("정지 실패: " + msg(e)); return Unit.INSTANCE; }), Math.abs(s.getValue()) * 1000L);
                    return Unit.INSTANCE; }, e -> { d.fail(msg(e)); return Unit.INSTANCE; });
                return Unit.INSTANCE; }, e -> { d.fail(msg(e)); return Unit.INSTANCE; });
            return Unit.INSTANCE; }, e -> { d.fail(msg(e)); return Unit.INSTANCE; });
    }

    private static void notifyUser(String text) {
        NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel("routine", "루틴 알림", NotificationManager.IMPORTANCE_HIGH));
        nm.notify((int) (System.currentTimeMillis() & 0xffffff), new Notification.Builder(app, "routine").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("루틴").setContentText(text).build());
    }

    // 내비: 즐겨찾기에서 찾아 경로 요청 (안내 시작은 내비 앱이 함)
    private static void navi(final Step s, final Done d) {
        final String want = s.getArg().trim();
        if (want.isEmpty()) { d.fail("목적지를 정해 주세요 (편집에서 즐겨찾기 이름 입력)"); return; }
        if (navi == null) navi = new NaviHelper(app);
        final Runnable ask = () -> {
            navi.addListener(new NaviHelperEventListener() {
                @Override public void onBookmarkInfo(List<BookmarkInfo> list) {
                    navi.removeListener(this);
                    // 집/회사는 분류(HOME/OFFICE)로도 찾음
                    BookmarkInfo hit = null;
                    for (BookmarkInfo b : list) {
                        boolean home = want.equals("집") || want.equalsIgnoreCase("home"), office = want.equals("회사") || want.equalsIgnoreCase("office");
                        if ((home && b.getCategory() == BookmarkType.HOME) || (office && b.getCategory() == BookmarkType.OFFICE) || (b.getName() != null && b.getName().contains(want))) { hit = b; break; }
                    }
                    // 즐겨찾기는 있는데 좌표가 없는 경우
                    if (hit != null && hit.getLatitude() == 0.0 && hit.getLongitude() == 0.0) { d.fail("내비의 ‘" + want + "’ 즐겨찾기가 비어 있습니다 (내비에서 위치를 먼저 등록해 주세요)"); return; }
                    if (hit == null) { d.fail("내비 즐겨찾기에 ‘" + want + "’이(가) 없습니다 (" + list.size() + "개 중)"); return; }
                    RouteOption opt; try { opt = RouteOption.valueOf(s.getOpt()); } catch (Exception e) { opt = RouteOption.RECOMMENDED; }
                    try {
                        // 요청까지만. 안내 시작은 내비 앱이 정함
                        navi.requestRoute(new RouteInfo(hit.getLatitude(), hit.getLongitude(), hit.getDisplayLatitude(), hit.getDisplayLongitude(), nz(hit.getName()), nz(hit.getPoiId()), nz(hit.getAddress()), nz(hit.getPoiSubId()), opt, nz(hit.getPoiProvider()), false));
                        d.ok("‘" + hit.getName() + "’ 경로 요청");
                    } catch (Throwable t) { android.util.Log.i("ROUTINE", "requestRoute", t); d.fail(msg(t)); }
                }
            });
            navi.getBookmarkInfo();
        };
        if (naviReady) ask.run();
        else navi.initialize(() -> { naviReady = true; ask.run(); return Unit.INSTANCE; }, e -> { d.fail("내비 연결 실패: " + msg(e)); return Unit.INSTANCE; });
    }
}
