package yeow.profile.instrumentation;

/** 任务优先级（独立枚举，避免对 yeow.Scheduler 的依赖）。 */
public enum TaskPriority {
    HIGH, NORMAL, LOW
}
