package arc.record.funcs;

import arc.record.SettingsAndUtils;
import arc.record.aff.Aff;

import java.io.File;
import java.util.Objects;

/**
 * @author MengLeiFudge
 */
public class Test {
    public void process() {
        test1();
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
}
