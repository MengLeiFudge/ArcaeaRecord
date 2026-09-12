package arc.record.aff.judge;

import arc.record.aff.note.ArcTap;
import arc.record.aff.note.Click;
import arc.record.aff.note.Hold;
import arc.record.aff.note.Note;

/** 标准地面/天空输入的纯空间规则，生成器选点和独立回放共用公式而不共享判定结论。 */
public final class InputJudgementRange {
    private InputJudgementRange() {
    }

    /**
     * 判断一次按下是否落在地键、未解锁Hold或Arctap的判定区域内。
     *
     * @param note 原谱物件，只有位置参与本次空间判断
     * @param position 反解或计划的实际AFF触点位置
     * @param ratio46k 当前4K/6K过渡比例
     * @return 空间符合时为true，仍需独立检查时间、边沿及物件优先级
     */
    public static boolean coversPress(Note note, AffPoint position, double ratio46k) {
        if (note instanceof Click click) return coversLane(click.getTrack(), position, ratio46k);
        if (note instanceof Hold hold) return coversLane(hold.getTrack(), position, ratio46k);
        ArcTap tap = (ArcTap) note;
        AffPoint touch = skyPosition(position, tap.getY(), ratio46k);
        double dx = Math.abs(touch.x() - tap.getX()) * 8.5;
        double dy = (touch.y() - tap.getY()) * 4.5;
        return dx <= 3.02 && dy >= -3.1 && dy <= 2.5;
    }

    /** 地面触点须位于天空中线下方；左右越界触点归最近有效轨道。 */
    public static boolean coversLane(int lane, AffPoint position, double ratio46k) {
        if (position.y() > (1 + 0.61 * ratio46k) / 2.0) return false;
        double center = lane * 0.5 - 0.75;
        double min = ratio46k > 0 ? -0.75 : -0.25;
        double max = ratio46k > 0 ? 1.75 : 1.25;
        return Math.abs(Math.clamp(position.x(), min, max) - center) <= 0.25;
    }

    /** 将普通天空物件的上下越界触点钳回边界，高于天空线的物件保留真实输入高度。 */
    public static AffPoint skyPosition(AffPoint position, double noteY, double ratio46k) {
        double top = 1 + 0.61 * ratio46k;
        double y = Math.max(0, position.y());
        if (noteY <= top) y = Math.min(top, y);
        return new AffPoint(position.x(), y);
    }
}
