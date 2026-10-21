package com.ysh.studiotool.executor;

/**
 * Gradle 任务日志精简过滤器。
 * 大项目日志经常超过 6 万字节，对 Agent 而言绝大多数内容无用；
 * 失败时的详细报错已由 GradleExecutor 结果的 error 字段承载（onFailure 的异常信息），
 * 因此 message 只保留日志末尾的结果汇总（如 "BUILD SUCCESSFUL in 8s"、"BUILD FAILED in 5s"、
 * "11 actionable tasks: ..."），其余内容以省略标注代替。
 */
public final class GradleLogFilter {

    private static final int TAIL_LINES = 10;

    private static final String OMITTED_NOTE =
            "... [已省略 %d 行日志，仅保留末尾结果信息，完整输出见 IDE 的 Gradle/Build 控制台] ...";

    private GradleLogFilter() {
    }

    /**
     * 精简 Gradle 日志：省略标注 + 末尾若干行结果汇总。
     * 日志本身很短时原样返回。
     */
    public static String filter(String log) {
        if (log == null || log.isBlank()) {
            return log;
        }
        String[] lines = log.split("\r?\n", -1);
        int n = lines.length;
        if (n > 0 && lines[n - 1].isEmpty()) {
            n--; // 去掉末尾换行产生的空元素
        }
        if (n <= TAIL_LINES) {
            return log;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(OMITTED_NOTE, n - TAIL_LINES)).append('\n');
        for (int i = n - TAIL_LINES; i < n; i++) {
            sb.append(lines[i]).append('\n');
        }
        return sb.toString();
    }
}
