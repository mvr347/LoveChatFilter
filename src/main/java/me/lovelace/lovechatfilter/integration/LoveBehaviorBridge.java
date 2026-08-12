package me.lovelace.lovechatfilter.integration;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.UUID;

/**
 * Мост к LoveBehavior. У LoveBehavior нет опубликованного API-артефакта на classpath
 * (аналогично LoveClans в LoveBehavior/LoveClansBridge), поэтому интеграция сделана через
 * reflection поверх Bukkit ServicesManager: сервис-провайдер, зарегистрированный плагином
 * LoveBehavior, опрашивается на методы applyAutoModPenalty(UUID, String, int) и
 * applyPoliteBonus(UUID, String). Если LoveBehavior не подключён, методы отсутствуют или вызов
 * завершился ошибкой — сигнал просто теряется, LoveChatFilter продолжает работать как обычный
 * фильтр без каких-либо последствий для репутации.
 */
public final class LoveBehaviorBridge {

    private final JavaPlugin plugin;

    public LoveBehaviorBridge(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void reportViolation(Player player, String violationType, int severity) {
        Object api = findLoveBehaviorApi();
        if (api == null) {
            return;
        }
        try {
            Method method = api.getClass().getMethod("applyAutoModPenalty", UUID.class, String.class, int.class);
            method.invoke(api, player.getUniqueId(), violationType, severity);
        } catch (NoSuchMethodException ignored) {
            // Установлена более старая версия LoveBehavior без поддержки авто-модерации.
        } catch (Exception e) {
            plugin.getLogger().fine("Не удалось вызвать LoveBehaviorAPI#applyAutoModPenalty: " + e.getMessage());
        }
    }

    public void reportPolitePhrase(Player player, String phrase) {
        Object api = findLoveBehaviorApi();
        if (api == null) {
            return;
        }
        try {
            Method method = api.getClass().getMethod("applyPoliteBonus", UUID.class, String.class);
            method.invoke(api, player.getUniqueId(), phrase);
        } catch (NoSuchMethodException ignored) {
        } catch (Exception e) {
            plugin.getLogger().fine("Не удалось вызвать LoveBehaviorAPI#applyPoliteBonus: " + e.getMessage());
        }
    }

    private Object findLoveBehaviorApi() {
        Plugin loveBehavior = Bukkit.getPluginManager().getPlugin("LoveBehavior");
        if (loveBehavior == null || !loveBehavior.isEnabled()) {
            return null;
        }
        Collection<RegisteredServiceProvider<?>> registrations = Bukkit.getServicesManager().getRegistrations(loveBehavior);
        if (registrations.isEmpty()) {
            return null;
        }
        return registrations.iterator().next().getProvider();
    }
}
