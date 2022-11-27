package arcaea.record.funcs;

import arcaea.record.SettingsAndUtils;
import arcaea.record.aff.Aff;

import java.io.File;
import java.util.Objects;

public class Test {
    public void process() {
        test1();
        //test2();
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
}
