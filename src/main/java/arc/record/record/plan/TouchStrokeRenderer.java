package arc.record.record.plan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import arc.record.aff.Aff;
import arc.record.aff.judge.AffPoint;
import arc.record.record.model.Resolution;
import arc.record.record.model.SimpleAction;
import arc.record.record.model.TouchIdManager;

import static arc.record.Settings.FIRST_NOTE_TIME;
import static arc.record.Settings.TOUCH_MOVE_DISTANCE_RATIO;
import static arc.record.Settings.CLICK_TIME;

/**
 * 将连续触控段落按屏幕移动距离渲染为 record 基础操作。
 */
public final class TouchStrokeRenderer {
    private static final int MAX_SUBDIVISION_DEPTH = 16;

    /**
     * 渲染指定分辨率下的全部触控段落。
     *
     * @param aff        谱面及 4K/6K 投影状态
     * @param strokes    与分辨率无关的连续触控段落
     * @param resolution 输出分辨率
     * @return 已分配触控 ID 且按时间排序的基础操作
     */
    public List<SimpleAction> render(Aff aff, List<TouchStroke> strokes, Resolution resolution) {
        double moveDistance = Math.max(1.0, resolution.getMaxY() * TOUCH_MOVE_DISTANCE_RATIO);
        TouchIdManager idManager = new TouchIdManager();
        List<SimpleAction> result = new ArrayList<>();
        for (TouchStroke stroke : strokes) {
            List<RenderedEvent> downEvents = renderDownEvents(
                    aff, stroke, resolution, moveDistance);
            int beginTime = downEvents.getFirst().timing();
            int releaseTime = (int) Math.round(stroke.endTime());
            int lastDownTime = downEvents.getLast().timing();
            if (releaseTime - lastDownTime < TouchStroke.MIN_PRESS_DURATION_MILLIS) {
                releaseTime = lastDownTime + TouchStroke.MIN_PRESS_DURATION_MILLIS;
            }
            int id = idManager.getId(beginTime, releaseTime);
            for (RenderedEvent event : downEvents) {
                result.add(new SimpleAction(
                        event.timing() + FIRST_NOTE_TIME + CLICK_TIME,
                        id, event.x(), event.y(), true));
            }
            RenderedEvent last = downEvents.getLast();
            result.add(new SimpleAction(
                    releaseTime + FIRST_NOTE_TIME + CLICK_TIME,
                    id, last.x(), last.y(), false));
        }
        result.sort(Comparator.naturalOrder());
        return List.copyOf(result);
    }

    private List<RenderedEvent> renderDownEvents(Aff aff, TouchStroke stroke,
                                                  Resolution resolution, double moveDistance) {
        List<TouchAnchor> anchors = new ArrayList<>(stroke.anchors());
        TouchAnchor lastAnchor = anchors.getLast();
        if (stroke.endTime() > lastAnchor.time() + 1e-7) {
            anchors.add(new TouchAnchor(
                    stroke.endTime(), lastAnchor.position(), true, TouchAnchor.Transition.LINEAR));
        }

        TreeMap<Integer, RenderedEvent> events = new TreeMap<>();
        ProjectedPoint initial = project(
                aff, resolution, anchors.getFirst(), anchors.getFirst(), anchors.getFirst().time());
        putEvent(events, initial, true, aff);
        RenderProgress progress = new RenderProgress(initial, 0);
        for (int i = 1; i < anchors.size(); i++) {
            TouchAnchor from = anchors.get(i - 1);
            TouchAnchor to = anchors.get(i);
            if (to.transition() == TouchAnchor.Transition.STEP) {
                progress = renderStepSegment(
                        aff, resolution, from, to, moveDistance, events, progress);
            } else {
                progress = renderLinearSegment(
                        aff, resolution, from, to, moveDistance, events, progress);
            }
        }
        return List.copyOf(events.values());
    }

    private RenderProgress renderStepSegment(
            Aff aff, Resolution resolution, TouchAnchor from, TouchAnchor to,
            double moveDistance, Map<Integer, RenderedEvent> events,
            RenderProgress progress) {
        TouchAnchor held = new TouchAnchor(
                to.time(), from.position(), false, TouchAnchor.Transition.LINEAR);
        progress = renderLinearSegment(
                aff, resolution, from, held, moveDistance, events, progress);
        ProjectedPoint target = project(aff, resolution, to, to, to.time());
        boolean changed = progress.lastEmitted().screenX() != target.screenX()
                || progress.lastEmitted().screenY() != target.screenY();
        int timing = (int) Math.round(target.time());
        if (changed || events.containsKey(timing)) {
            if (putEvent(events, target, to.required(), aff)) {
                progress = new RenderProgress(target, 0);
            }
        }
        return new RenderProgress(progress.lastEmitted(), 0);
    }

    private RenderProgress renderLinearSegment(
            Aff aff, Resolution resolution, TouchAnchor from, TouchAnchor to,
            double moveDistance, Map<Integer, RenderedEvent> events,
            RenderProgress progress) {
        List<ProjectedPoint> polyline = new ArrayList<>();
        ProjectedPoint start = project(aff, resolution, from, to, from.time());
        polyline.add(start);
        for (double splitTime : projectionSplitTimes(aff, from.time(), to.time())) {
            ProjectedPoint split = project(aff, resolution, from, to, splitTime);
            subdivide(aff, resolution, from, to, start, split,
                    moveDistance, 0, polyline);
            start = split;
        }
        ProjectedPoint end = project(aff, resolution, from, to, to.time());
        subdivide(aff, resolution, from, to, start, end,
                moveDistance, 0, polyline);
        progress = addDistanceEvents(
                aff, resolution, from, to, polyline, moveDistance, events, progress);
        if (!to.required()) {
            return progress;
        }

        boolean changed = progress.lastEmitted().screenX() != end.screenX()
                || progress.lastEmitted().screenY() != end.screenY();
        int timing = (int) Math.round(end.time());
        if ((changed || events.containsKey(timing)) && putEvent(events, end, true, aff)) {
            progress = new RenderProgress(end, 0);
        }
        return new RenderProgress(progress.lastEmitted(), 0);
    }

    private static List<Double> projectionSplitTimes(Aff aff, double from, double to) {
        return aff.getSceneControlList().stream()
                .flatMap(control -> java.util.stream.Stream.of(
                        (double) control.getT(),
                        control.getT() + control.getDuration()))
                .filter(time -> time > from && time < to)
                .distinct()
                .sorted()
                .toList();
    }

    private void subdivide(Aff aff, Resolution resolution,
                           TouchAnchor from, TouchAnchor to,
                           ProjectedPoint start, ProjectedPoint end,
                           double threshold, int depth,
                           List<ProjectedPoint> output) {
        if (depth >= MAX_SUBDIVISION_DEPTH || end.time() - start.time() <= 1e-7) {
            output.add(end);
            return;
        }
        double middleTime = (start.time() + end.time()) / 2.0;
        ProjectedPoint middle = project(aff, resolution, from, to, middleTime);
        double chord = distance(start, end);
        double deviation = distanceToSegment(middle, start, end);
        if (chord <= threshold / 2.0 && deviation <= threshold / 8.0) {
            output.add(end);
            return;
        }
        subdivide(aff, resolution, from, to, start, middle,
                threshold, depth + 1, output);
        subdivide(aff, resolution, from, to, middle, end,
                threshold, depth + 1, output);
    }

    private RenderProgress addDistanceEvents(
            Aff aff, Resolution resolution, TouchAnchor from, TouchAnchor to,
            List<ProjectedPoint> polyline, double threshold,
            Map<Integer, RenderedEvent> events, RenderProgress progress) {
        double accumulated = progress.accumulatedDistance();
        ProjectedPoint lastEmitted = progress.lastEmitted();
        ProjectedPoint segmentStart = polyline.getFirst();
        for (int i = 1; i < polyline.size(); i++) {
            ProjectedPoint segmentEnd = polyline.get(i);
            double length = distance(segmentStart, segmentEnd);
            while (length > 1e-7 && accumulated + length >= threshold) {
                double fraction = (threshold - accumulated) / length;
                double eventTime = segmentStart.time()
                        + (segmentEnd.time() - segmentStart.time()) * fraction;
                ProjectedPoint event = project(aff, resolution, from, to, eventTime);
                if (event.screenX() != lastEmitted.screenX()
                        || event.screenY() != lastEmitted.screenY()) {
                    if (putEvent(events, event, false, aff)) {
                        lastEmitted = event;
                    }
                }
                segmentStart = event;
                length = distance(segmentStart, segmentEnd);
                accumulated = 0;
            }
            accumulated += length;
            segmentStart = segmentEnd;
        }
        return new RenderProgress(lastEmitted, accumulated);
    }

    private ProjectedPoint project(Aff aff, Resolution resolution,
                                   TouchAnchor from, TouchAnchor to, double time) {
        double ratio = to.time() == from.time()
                ? 0
                : (time - from.time()) / (to.time() - from.time());
        ratio = Math.max(0, Math.min(1, ratio));
        AffPoint first = from.position();
        AffPoint second = to.position();
        double x = first.x() + (second.x() - first.x()) * ratio;
        double y = first.y() + (second.y() - first.y()) * ratio;
        int[] screen = resolution.convertToXY(x, y, aff.getRatio46k(time));
        return new ProjectedPoint(time, screen[0], screen[1]);
    }

    private boolean putEvent(Map<Integer, RenderedEvent> events, ProjectedPoint point,
                             boolean required, Aff aff) {
        int timing = (int) Math.round(point.time());
        RenderedEvent candidate = new RenderedEvent(
                timing, point.screenX(), point.screenY(), required);
        RenderedEvent existing = events.get(timing);
        if (existing == null) {
            events.put(timing, candidate);
            return true;
        }
        if (existing.x() == candidate.x() && existing.y() == candidate.y()) {
            if (required && !existing.required()) {
                events.put(timing, candidate);
            }
            return true;
        }
        if (existing.required() && required) {
            throw new IllegalStateException(
                    "同一触控在同一整数毫秒需要两个位置：" + aff.getAffFile().getAbsolutePath()
                            + "，timing=" + timing);
        }
        if (required || !existing.required()) {
            events.put(timing, candidate);
            return true;
        }
        return false;
    }

    private static double distance(ProjectedPoint first, ProjectedPoint second) {
        return Math.hypot(first.screenX() - second.screenX(), first.screenY() - second.screenY());
    }

    private static double distanceToSegment(ProjectedPoint point,
                                            ProjectedPoint start, ProjectedPoint end) {
        double dx = end.screenX() - start.screenX();
        double dy = end.screenY() - start.screenY();
        double denominator = dx * dx + dy * dy;
        if (denominator == 0) {
            return distance(point, start);
        }
        double ratio = ((point.screenX() - start.screenX()) * dx
                + (point.screenY() - start.screenY()) * dy) / denominator;
        ratio = Math.max(0, Math.min(1, ratio));
        double x = start.screenX() + dx * ratio;
        double y = start.screenY() + dy * ratio;
        return Math.hypot(point.screenX() - x, point.screenY() - y);
    }

    private record ProjectedPoint(double time, int screenX, int screenY) {
    }

    private record RenderProgress(ProjectedPoint lastEmitted, double accumulatedDistance) {
    }

    private record RenderedEvent(int timing, int x, int y, boolean required) {
    }
}
