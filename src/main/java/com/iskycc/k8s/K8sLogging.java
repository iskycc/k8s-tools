package com.iskycc.k8s;

/**
 * 本库诊断日志的全局开关，同一 classloader 中的所有客户端和 SSH/Redis 组件共用。
 * 默认关闭 DEBUG，保留 INFO、WARN、ERROR；最终输出仍由应用的 SLF4J provider 控制。
 *
 * <p>启动时通过 {@code -Dk8s.tools.debug=true} 开启，或运行时调用
 * {@link #setDebugEnabled(boolean)}。开启后还需将 {@code com.iskycc.k8s} 的日志级别设为 DEBUG。
 * 不修改第三方组件、应用日志框架或 CLI 的业务查询输出。
 */
public final class K8sLogging {
    /** JVM 启动参数名称，仅在本类初始化时读取。 */
    public static final String DEBUG_PROPERTY = "k8s.tools.debug";

    private static volatile boolean debugEnabled = Boolean.parseBoolean(System.getProperty(DEBUG_PROPERTY, "false"));

    private K8sLogging() { }

    /**
     * 切换所有实例后续的调试日志；运行时设置覆盖启动参数，不改变日志 provider 的级别。
     * @param enabled true 允许 DEBUG，false 禁止本库 DEBUG
     */
    public static void setDebugEnabled(boolean enabled) {
        debugEnabled = enabled;
    }

    /** @return 本库是否允许输出 DEBUG；不代表日志 provider 已开启 DEBUG */
    public static boolean isDebugEnabled() {
        return debugEnabled;
    }
}
