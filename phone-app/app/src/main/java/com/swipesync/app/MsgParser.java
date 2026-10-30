package com.swipesync.app;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 手表→手机消息解析。
 * 手表端 interconnect.send({ data: "<json string>" })，
 * 手机端 OnMessageReceivedListener 收到 byte[]，做 UTF-8 解码后按 JSON 解析。
 * 兼容三种情况：纯 JSON 对象 / JSON 字符串包裹的 JSON / 裸文本含 "d":"dir"。
 */
public final class MsgParser {

    private static final Pattern DIR_PATTERN =
            Pattern.compile("\"d\"\\s*:\\s*\"(up|down|left|right)\"");
    private static final Pattern MODE_PATTERN =
            Pattern.compile("\"m\"\\s*:\\s*\"([dc])\"");
    private static final Pattern SEQ_PATTERN =
            Pattern.compile("\"s\"\\s*:\\s*(\\d+)");

    private MsgParser() {
    }

    /** 返回 null 表示无法识别的消息 */
    public static SwipeEvent parse(byte[] message) {
        if (message == null || message.length == 0) {
            return null;
        }
        String s = new String(message, StandardCharsets.UTF_8).trim();
        if (s.isEmpty()) {
            return null;
        }

        // 1) 直接是 JSON 对象
        SwipeEvent ev = fromJson(s);
        if (ev != null) {
            return ev;
        }

        // 2) JSON 字符串包裹（框架可能对字符串 data 加引号转义）
        try {
            Object v = new JSONTokener(s).nextValue();
            if (v instanceof String) {
                ev = fromJson(((String) v).trim());
                if (ev != null) {
                    return ev;
                }
            }
        } catch (Exception ignored) {
        }

        // 3) 正则兜底
        Matcher dm = DIR_PATTERN.matcher(s);
        if (dm.find()) {
            String dir = dm.group(1);
            boolean cont = false;
            Matcher mm = MODE_PATTERN.matcher(s);
            if (mm.find()) {
                cont = "c".equals(mm.group(1));
            }
            long seq = 0;
            Matcher sm = SEQ_PATTERN.matcher(s);
            if (sm.find()) {
                try {
                    seq = Long.parseLong(sm.group(1));
                } catch (NumberFormatException ignored) {
                }
            }
            return new SwipeEvent(dir, cont, seq);
        }
        return null;
    }

    private static SwipeEvent fromJson(String json) {
        if (json == null || !json.startsWith("{")) {
            return null;
        }
        try {
            JSONObject o = new JSONObject(json);
            if (!"sw".equals(optString(o, "t"))) {
                return null;
            }
            String dir = optString(o, "d");
            if (!SwipeEvent.isValidDir(dir)) {
                return null;
            }
            boolean cont = "c".equals(optString(o, "m"));
            long seq = o.optLong("s", 0L);
            return new SwipeEvent(dir, cont, seq);
        } catch (Exception e) {
            return null;
        }
    }

    private static String optString(JSONObject o, String key) {
        String v = o.optString(key, "");
        return v == null ? "" : v;
    }
}
