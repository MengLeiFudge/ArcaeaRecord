package arcaea.record.funcs;

import arcaea.record.SettingsAndUtils;
import arcaea.record.aff.Aff;

import java.io.File;
import java.util.Objects;

public class Test {
    public void process() {
        //test1();
        test2();
    }

    void test1(){
        for (var songDir : Objects.requireNonNull(SettingsAndUtils.AFF_DIR.listFiles())) {
            if (songDir.isDirectory()) {
                System.out.print(songDir.getName() + ": ");
                for (var affFile : Objects.requireNonNull(songDir.listFiles())) {
                    if (affFile.getName().endsWith("aff")) {
                        Aff aff = new Aff(affFile);
                        System.out.print(aff.getNote() + ", ");
                    }
                }
                System.out.println();
            }
        }
    }

    void test2(){
        Aff aff = new Aff(new File("D:\\arc\\官谱\\brandnewworld\\0.aff"));
        System.out.println(aff.getNote());
    }
}
