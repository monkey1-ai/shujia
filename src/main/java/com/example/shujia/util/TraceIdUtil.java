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
    /**
     * 把【已存在的】traceId 绑定到当前线程。
     *
     * 【新增原因】ThreadLocal 是"每个线程一个私有储物柜"，柜子之间不互通。
     * 请求线程靠 TraceIdFilter.generate() 存了一个 id，但业务跑在 runAsync 开的
     * 异步线程里 —— 异步线程的柜子是空的，调 get() 会走下面的惰性生成，又造一个
     * 新 id，于是日志里的 id 和请求入口的 id 对不上（"id 有些是错的"就是这么来的）。
     *
     * 所以进入异步线程后，先手动把请求线程的 id 抄过来，get() 就不会再造新的。
     * 注意：区别于 generate() —— generate() 是"造一个新的并存下"，set() 只是"存下这个"。
     */
    public static void set(String traceId) {
        traceIdHolder.set(traceId);
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
