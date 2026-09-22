package com.example.shujia.util;

import java.util.UUID;

public class TraceIdUtil {
    private static final ThreadLocal<String> traceIdHolder = new ThreadLocal<>();
    public static String generate() {
        String traceId = UUID.randomUUID()
                            .toString()
                            .replace("-", "")
                            .substring(0, 16);
        traceIdHolder.set(traceId);
        return traceId;
    }
    public static String get() {
        String traceId = traceIdHolder.get();
        if(traceId == null) {
            traceId= generate();
        }
        return traceId;
    }
    public static void clear() {
        traceIdHolder.remove();
    }
}
