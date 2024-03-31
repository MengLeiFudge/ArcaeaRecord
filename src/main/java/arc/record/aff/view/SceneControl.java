package arc.record.aff.view;

import java.io.Serializable;

import lombok.Data;

/**
 * 仅表示参数为 enwidencamera 的语句.
 *
 * @author MengLeiFudge
 */
@Data
public class SceneControl implements Serializable, Comparable<SceneControl> {
    /**
     * 起始时间.
     */
    final int t;

    /**
     * 持续时间.
     */
    final double duration;

    /**
     * 淡入或淡出该事件展示的效果（1/0），1表示变为6k、显示边轨.
     * <p>
     * true 表示4k变6k，false 表示6k变4k.
     */
    final boolean to6k;

    public SceneControl(String line) {
        String[] data = line.substring("scenecontrol(".length(), line.length() - 2).split(",");
        t = Integer.parseInt(data[0]);
        // data[1] 是场景参数，此处仅处理 enwidencamera
        duration = Double.parseDouble(data[2]);
        to6k = Integer.parseInt(data[3]) == 1;
    }

    @Override
    public int compareTo(SceneControl o) {
        return t - o.t;
    }
}
