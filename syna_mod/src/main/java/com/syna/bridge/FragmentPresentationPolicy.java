package com.syna.bridge;

final class FragmentPresentationPolicy {
    private FragmentPresentationPolicy() {}

    static int mood(int boredom, String phase, String horrorStage) {
        if (boredom >= 100 || !"dormant".equals(phase)
                || "hunting".equals(horrorStage) || "game".equals(horrorStage)) return 3;
        if (boredom >= 70 || (horrorStage != null && !"calm".equals(horrorStage))) return 2;
        if (boredom >= 35) return 1;
        return 0;
    }

    static String title(int mood) {
        return switch (mood) {
            case 1 -> "Syna 的残页·划痕";
            case 2 -> "Syna 的残页·冰冷";
            case 3 -> "Syna 的残页·规则";
            default -> "Syna 的残页";
        };
    }

    static String omen(int mood) {
        return switch (mood) {
            case 1 -> "边缘多了几道新划痕。";
            case 2 -> "纸页冰冷，墨迹压成暗红。";
            case 3 -> "最后一行换了字迹：听清规则。";
            default -> "字迹很淡，末尾还留着空白。";
        };
    }
}
