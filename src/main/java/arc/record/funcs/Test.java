package arc.record.funcs;

import arc.record.SettingsAndUtils;
import arc.record.aff.Aff;
import arc.record.record.data.Resolution;

import java.io.File;
import java.util.Arrays;
import java.util.Objects;

/**
 * @author MengLeiFudge
 */
public class Test {
    public void process() {
        test4();
    }

    void test1() {
        for (var songDir : Objects.requireNonNull(SettingsAndUtils.AFF_DIR.listFiles())) {
            if (songDir.isDirectory()) {
                System.out.printf("%25s", songDir.getName());
                for (int i = 0; i < 4; i++) {
                    File affFile = new File(songDir, i + ".aff");
                    if (!affFile.exists()) {
                        continue;
                    }
                    Aff aff = new Aff(affFile);
                    System.out.printf("%8d", aff.getNoteCount());
                }
                System.out.println();
            }
        }
    }

    void test2() {
        Aff aff = new Aff(new File("D:\\arc\\官谱\\anokumene\\0.aff"));
        System.out.println(aff.getNoteCount());
    }

    void test3() {
        Resolution r = Resolution.R16_9_1280_720;
        System.out.println(Arrays.toString(r.convertToXY(-0.5, 0, 0)));
        System.out.println(Arrays.toString(r.convertToXY(1.5, 0, 0)));
        System.out.println(Arrays.toString(r.convertToXY(0, 1, 0)));
        System.out.println(Arrays.toString(r.convertToXY(1, 1, 0)));
        System.out.println();
        System.out.println(Arrays.toString(r.convertToXY(-0.5, 0, 1)));
        System.out.println(Arrays.toString(r.convertToXY(1.5, 0, 1)));
        System.out.println(Arrays.toString(r.convertToXY(0, 1, 1)));
        System.out.println(Arrays.toString(r.convertToXY(1, 1, 1)));
        System.out.println();
    }

    void test4() {
        Resolution r1 = Resolution.R16_9_1280_720;
        System.out.println(r1.getPreSimpleActions());
        Resolution r2 = Resolution.R16_9_960_540;
        System.out.println(r2.getPreSimpleActions());
        Resolution r3 = Resolution.R16_9_1280_720;
        System.out.println(r3.getPreSimpleActions());
    }
}
