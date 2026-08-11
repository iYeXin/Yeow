package yeow.profile.warnings;

import java.util.List;

/** 一条告警：级别 + 稳定 code + 归属插件 + 双语描述。 */
public record Warning(
    WarningLevel level,
    String code,
    String plugin,
    String title,
    List<String> lines
) {}
