package me.lovelace.lovechatfilter.managers;

import me.lovelace.lovechatfilter.LoveChatFilter;
import org.bukkit.entity.Player;

public class GrammarManager {
    private final LoveChatFilter plugin;

    public GrammarManager(LoveChatFilter plugin) {
        this.plugin = plugin;
    }

    // Метод добавлен для успешной компиляции и работы /acf reload
    public void reload() {
        // Оставлен пустым, так как настройки хранятся в БД
    }

    public boolean isEnabled(Player player) {
        return plugin.getDatabaseManager().isGrammarEnabled(player.getUniqueId());
    }

    @SuppressWarnings("unused")
    public boolean toggle(Player player) {
        boolean newState = !isEnabled(player);
        plugin.getDatabaseManager().setGrammarEnabled(player.getUniqueId(), newState);
        return newState;
    }

    public String applyGrammar(String message) {
        if (message == null || message.trim().isEmpty()) return message;

        char[] chars = message.toCharArray();
        boolean capitalizeNext = true;

        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];

            if (capitalizeNext && Character.isLetter(c)) {
                chars[i] = Character.toUpperCase(c);
                capitalizeNext = false;
            } else if (c == '.' || c == '!' || c == '?') {
                if (i == chars.length - 1 || Character.isWhitespace(chars[i + 1])) {
                    capitalizeNext = true;
                }
            }
        }

        // Преобразуем обратно в строку и убираем случайные пробелы в самом конце
        String result = new String(chars).trim();

        // Автоматически добавляем точку в конце, если нет других знаков завершения
        if (!result.isEmpty()) {
            char lastChar = result.charAt(result.length() - 1);
            // Если последний символ НЕ точка, НЕ воскл. знак, НЕ вопр. знак и НЕ скобка (от смайлика)
            if (lastChar != '.' && lastChar != '!' && lastChar != '?' && lastChar != ')') {
                result += ".";
            }
        }

        return result;
    }
}