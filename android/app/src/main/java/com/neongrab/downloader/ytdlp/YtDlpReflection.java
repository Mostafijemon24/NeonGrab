package com.neongrab.downloader.ytdlp;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

final class YtDlpReflection {

    private YtDlpReflection() {}

    static Object youtubeDlInstance() throws Exception {
        Class<?> cls = Class.forName("com.yausername.youtubedl_android.YoutubeDL");
        return cls.getMethod("getInstance").invoke(null);
    }

    static Object ffmpegInstance() throws Exception {
        Class<?> cls = Class.forName("com.yausername.ffmpeg.FFmpeg");
        return cls.getMethod("getInstance").invoke(null);
    }

    static void setField(Object target, String name, Object value) throws Exception {
        Class<?> cls = target.getClass();
        Field field = null;
        for (Field f : cls.getDeclaredFields()) {
            if (f.getName().equals(name)) {
                field = f;
                break;
            }
        }
        if (field == null) {
            throw new NoSuchFieldException(name + " on " + cls.getName());
        }
        field.setAccessible(true);
        field.set(target, value);
    }

    static void invoke(Object target, String name, Class<?>[] types, Object... args)
            throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        method.invoke(target, args);
    }
}
