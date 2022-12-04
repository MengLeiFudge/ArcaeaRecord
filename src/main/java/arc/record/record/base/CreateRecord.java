package arc.record.record.base;

import arc.record.record.data.SimpleAction;
import com.alibaba.fastjson2.JSONArray;

import java.io.File;

@Deprecated
public class CreateRecord {

    /*-- 生成脚本 --*/

    /**
     * 将某个脚本保存至指定目录.
     */
    public void save(File targetDir, String song, String difficult,
                     int miss, int minPure, boolean haveBegin, boolean mirror) {
        /*JSONArray operationsArray = new JSONArray();
        // 写入 PreSimpleActions，即暂停、继续那些操作
        if (haveBegin) {
            List<SimpleAction> preSimpleActions = resolution.getPreSimpleActions();
            for (int i = 0; i < preSimpleActions.size(); i++) {
                int timing = preSimpleActions.get(i).getTiming();
                JSONArray points = new JSONArray();
                for (int j = i; j < preSimpleActions.size(); j++) {
                    SimpleAction o1 = preSimpleActions.get(j);
                    if (o1.getTiming() == timing) {
                        add(points, o1, false);
                        i++;
                    } else {
                        break;
                    }
                }
                i--;
                JSONObject obj = new JSONObject();
                obj.put("timing", timing);
                obj.put("operationId", "PutMultiTouch");
                obj.put("points", points);
                operationsArray.add(obj);
            }
        }
        // 写入已经处理好的脚本操作
        for (int i = 0; i < simpleActions.size(); i++) {
            int timing = simpleActions.get(i).getTiming();
            JSONArray points = new JSONArray();
            for (int j = i; j < simpleActions.size(); j++) {
                SimpleAction o1 = simpleActions.get(j);
                if (o1.getTiming() == timing) {
                    add(points, o1, mirror);
                    i++;
                } else {
                    break;
                }
            }
            i--;
            JSONObject obj = new JSONObject();
            obj.put("timing", timing);
            obj.put("operationId", "PutMultiTouch");
            obj.put("points", points);
            operationsArray.add(obj);
        }
        JSONObject recordInfo = new JSONObject();
        recordInfo.put("loopType", 0);
        recordInfo.put("loopTimes", 1);
        recordInfo.put("circleDuration", recordEndTime);
        recordInfo.put("loopInterval", 0);
        recordInfo.put("loopDuration", 0);
        recordInfo.put("accelerateTimes", 1);
        recordInfo.put("recordName", "");
        String s = difficult +
                (haveBegin ? "" : "_首键") +
                (mirror ? "_镜像_" : "_原版_") +
                miss + "L" + minPure + "小";
        recordInfo.put("createTime", s);
        recordInfo.put("playOnBoot", false);
        recordInfo.put("rebootTiming", 0);
        JSONObject obj = new JSONObject();
        obj.put("operations", operationsArray);
        obj.put("recordInfo", recordInfo);
        String formatStr = obj.toString(JSONWriter.Feature.PrettyFormat);// 80%时间
        targetDir.mkdirs();
        File recordFile = new File(targetDir, song + "_" + s + ".record");
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(recordFile))) {
            bw.write(formatStr);// 10%时间
        } catch (IOException e) {
            e.printStackTrace();
        }*/
    }

    private void add(JSONArray points, SimpleAction simpleAction, boolean mirror) {
        /*JSONObject obj = new JSONObject();
        obj.put("id", simpleAction.getId());
        obj.put("x", mirror ? resolution.getMaxX() - simpleAction.getX() : simpleAction.getX());
        obj.put("y", simpleAction.getY());
        obj.put("state", simpleAction.getState());
        points.add(obj);*/
    }

}
