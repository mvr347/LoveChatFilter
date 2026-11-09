package me.lovelace.lovechatfilter.managers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The filter settings the web panel may change. Only a fixed list of keys is accepted, each with a type and a range,
 * so the panel can never write an arbitrary path into the configuration. Pure and Bukkit-free: the plugin applies the
 * validated values to the in-memory config and keeps them in the database as overrides of config.yml.
 */
public final class WebSettings {
    private WebSettings() {}

    public enum Type { BOOL, INT, TEXT }

    /** One editable setting: its config path, type, bounds (for numbers: value range, for text: length) and label. */
    public record Setting(String key, Type type, int min, int max, String group, String label) {}

    private static final Map<String, Setting> ALL = new LinkedHashMap<>();

    private static void bool(String key, String group, String label) {
        ALL.put(key, new Setting(key, Type.BOOL, 0, 1, group, label));
    }

    private static void number(String key, int min, int max, String group, String label) {
        ALL.put(key, new Setting(key, Type.INT, min, max, group, label));
    }

    private static void text(String key, int maxLength, String group, String label) {
        ALL.put(key, new Setting(key, Type.TEXT, 1, maxLength, group, label));
    }

    static {
        bool("modules.badwords-filter", "Модули", "Фильтр нецензурной лексики");
        bool("modules.ads-filter", "Модули", "Фильтр рекламы");
        bool("modules.anti-caps", "Модули", "Анти-капс");
        bool("modules.anti-spam", "Модули", "Анти-спам");
        bool("modules.grammar-fix", "Модули", "Автоисправление пунктуации и регистра");
        bool("modules.goodwords-detector", "Модули", "Реакция на «хорошие» слова");
        bool("modules.auto-moderation", "Модули", "Сигналы для репутации (LoveBehavior)");
        bool("modules.emptymessages-clear", "Модули", "Блокировка пустых сообщений");
        bool("modules.self-filter", "Модули", "Игрок видит своё сообщение уже отфильтрованным");
        bool("badwords-filter.full-delete-message", "Мат", "Не отправлять сообщение с матом вообще");
        text("badwords-filter.replacement-char", 4, "Мат", "Символ замены (NONE - просто вырезать)");
        bool("badwords-filter.advanced.enabled", "Обход фильтра", "Распознавание обхода фильтра");
        bool("badwords-filter.advanced.homoglyph-detection", "Обход фильтра", "Похожие латинские буквы");
        bool("badwords-filter.advanced.digit-substitution-detection", "Обход фильтра", "Цифры вместо букв");
        bool("badwords-filter.advanced.fuzzy-matching", "Обход фильтра", "Опечатки");
        bool("badwords-filter.advanced.compact-detection", "Обход фильтра", "Разбитые и растянутые слова");
        bool("badwords-filter.advanced.transliteration-detection", "Обход фильтра", "Транслит");
        number("badwords-filter.advanced.max-typo-distance", 0, 3, "Обход фильтра", "Максимум опечаток");
        number("badwords-filter.advanced.min-fuzzy-word-length", 3, 12, "Обход фильтра", "Минимальная длина слова для опечаток");
        number("anti-caps.max-caps-percent", 1, 100, "Капс", "Процент заглавных букв для срабатывания");
        number("anti-spam.message-cooldown-seconds", 0, 60, "Спам", "Задержка между сообщениями, сек");
        number("anti-spam.same-message-cooldown-seconds", 0, 120, "Спам", "Задержка для одинакового сообщения, сек");
        number("anti-spam.max-repeated-chars", 0, 50, "Спам", "Предел повтора одного символа");
    }

    public static List<Setting> all() {
        return List.copyOf(ALL.values());
    }

    public static Setting find(String key) {
        return key == null ? null : ALL.get(key);
    }

    /**
     * Checks a value from the panel and returns it in the form stored in the config (Boolean, Integer or String).
     *
     * @throws IllegalArgumentException with a readable reason for an unknown key or a value out of range
     */
    public static Object validate(String key, Object raw) {
        Setting setting = find(key);
        if (setting == null) throw new IllegalArgumentException("Эту настройку менять нельзя.");
        String value = raw == null ? "" : raw.toString().strip();
        switch (setting.type()) {
            case BOOL -> {
                String lower = value.toLowerCase(Locale.ROOT);
                if (lower.equals("true")) return Boolean.TRUE;
                if (lower.equals("false")) return Boolean.FALSE;
                throw new IllegalArgumentException("Ожидается true или false.");
            }
            case INT -> {
                int number;
                try {
                    number = Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Ожидается целое число.");
                }
                if (number < setting.min() || number > setting.max())
                    throw new IllegalArgumentException("Допустимо от " + setting.min() + " до " + setting.max() + ".");
                return number;
            }
            default -> {
                if (value.isEmpty() || value.length() > setting.max())
                    throw new IllegalArgumentException("Длина от 1 до " + setting.max() + " символов.");
                for (int i = 0; i < value.length(); i++)
                    if (Character.isISOControl(value.charAt(i)))
                        throw new IllegalArgumentException("Управляющие символы недопустимы.");
                return value;
            }
        }
    }

    /** Where the overrides survive a restart. */
    public interface Store {
        Map<String, String> loadOverrides();

        void saveOverride(String key, String value);

        void deleteOverride(String key);
    }

    /** Rows for the panel: every setting with its current value, whether it is overridden, and its limits. */
    public static List<Map<String, Object>> describe(
            java.util.function.Function<String, Object> current, java.util.Set<String> overridden) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Setting s : ALL.values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", s.key());
            row.put("type", s.type().name().toLowerCase(Locale.ROOT));
            row.put("group", s.group());
            row.put("label", s.label());
            row.put("min", s.min());
            row.put("max", s.max());
            row.put("value", current.apply(s.key()));
            row.put("overridden", overridden.contains(s.key()));
            rows.add(row);
        }
        return rows;
    }
}
