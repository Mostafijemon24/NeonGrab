package com.neongrab.downloader.ytdlp;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.io.File;
import java.util.Map;
final class YtDlpReflection {

    private YtDlpReflection() {}

    static Object youtubeDlInstance() throws Exception {
        Class<?> cls = Class.forName("com.yausername.youtubedl_android.YoutubeDL");
        try {
            return cls.getMethod("getInstance").invoke(null);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new RuntimeException(cause != null ? cause.getMessage() : "getInstance failed", e);
        }
    }

    static Object ffmpegInstance() throws Exception {
        Class<?> cls = Class.forName("com.yausername.ffmpeg.FFmpeg");
        try {
            return cls.getMethod("getInstance").invoke(null);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new RuntimeException(cause != null ? cause.getMessage() : "getInstance failed", e);
        }
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
        try {
            method.invoke(target, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new RuntimeException(cause != null ? cause.getMessage() : "Invocation of " + name + " failed", e);
        }
    }

    static String getStringField(Object target, String name) throws Exception {
        Object v = getFieldObject(target, name);
        return v != null ? v.toString() : null;
    }

    static File getFileField(Object target, String name) throws Exception {
        Object v = getFieldObject(target, name);
        return v instanceof File ? (File) v : null;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Process> getProcessMap(Object ytdlp) throws Exception {
        Object map = getFieldObject(ytdlp, "idProcessMap");
        return (Map<String, Process>) map;
    }

    private static Object getFieldObject(Object target, String name) throws Exception {
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
        return field.get(target);
    }
}
