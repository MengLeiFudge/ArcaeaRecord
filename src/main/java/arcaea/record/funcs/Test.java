package arcaea.record.funcs;

import arcaea.record.SettingsAndUtils;
import arcaea.record.aff.Aff;
import arcaea.record.aff.Resolution;

import java.io.File;
import java.util.Objects;

public class Test {
    public void process() {
        test3();
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
        System.out.println(r.convertToX(-0.5, 0, 0) + "," + r.convertToY(0, 0));
        System.out.println(r.convertToX(1.5, 0, 0) + "," + r.convertToY(0, 0));
        System.out.println(r.convertToX(0, 1, 0) + "," + r.convertToY(1, 0));
        System.out.println(r.convertToX(1, 1, 0) + "," + r.convertToY(1, 0));
        System.out.println();
        System.out.println(r.convertToX(-0.5, 0, 1) + "," + r.convertToY(0, 1));
        System.out.println(r.convertToX(1.5, 0, 1) + "," + r.convertToY(0, 1));
        System.out.println(r.convertToX(0, 1, 1) + "," + r.convertToY(1, 1));
        System.out.println(r.convertToX(1, 1, 1) + "," + r.convertToY(1, 1));
        System.out.println();
    }
}
