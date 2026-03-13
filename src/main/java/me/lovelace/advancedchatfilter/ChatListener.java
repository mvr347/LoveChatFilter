package me.lovelace.advancedchatfilter;

import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

@SuppressWarnings({"deprecation", "UnstableApiUsage"})
public class ChatListener implements Listener {
    private final AdvancedChatFilter acf;

    public ChatListener(AdvancedChatFilter acf) {
        this.acf = acf;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        Component originalComponent = event.message();

        String originalText = PlainTextComponentSerializer.plainText().serialize(originalComponent);

        FilterEngine.ProcessResult result = acf.getFilterEngine().processChat(player, originalText);

        if (result.cancelled) {
            event.setCancelled(true);
            return;
        }

        String finalText = result.message;

        if (acf.getConfigManager().isModuleEnabled("grammar-fix")) {
            if (acf.getGrammarManager().isEnabled(player)) {
                finalText = acf.getGrammarManager().applyGrammar(finalText);
            }
        }

        if (!originalText.equals(finalText)) {
            boolean selfFilter = acf.getConfigManager().getConfig().getBoolean("self-filter", true);

            if (!selfFilter) {
                ChatRenderer originalRenderer = event.renderer();
                event.renderer((source, sourceDisplayName, message, viewer) -> {
                    if (viewer instanceof Player p && p.getUniqueId().equals(source.getUniqueId())) {
                        return originalRenderer.render(source, sourceDisplayName, originalComponent, viewer);
                    }
                    return originalRenderer.render(source, sourceDisplayName, message, viewer);
                });
            }

            event.message(Component.text(finalText));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String message = event.getMessage();
        if (message.length() < 2) return;

        String[] parts = message.split(" ", 2);
        String cmd = parts[0].substring(1).toLowerCase();

        // 1. Полностью игнорируемые команды (для паролей и авторизации)
        List<String> ignored = acf.getConfigManager().getConfig().getStringList("ignore-commands");
        if (ignored.contains(cmd)) return;

        if (parts.length > 1) {
            String args = parts[1];

            // 2. Читаем список команд, где разрешена грамматика
            List<String> grammarAllowed = acf.getConfigManager().getConfig().getStringList("grammar-allowed-commands");
            if (grammarAllowed.isEmpty()) {
                grammarAllowed = List.of("msg", "tell", "w", "r", "reply", "me", "say", "broadcast", "bc");
            }

            if (grammarAllowed.contains(cmd)) {
                // Полная проверка (грамматика, капс, мат, спам)
                FilterEngine.ProcessResult result = acf.getFilterEngine().processChat(event.getPlayer(), args);
                if (result.cancelled) {
                    event.setCancelled(true);
                    return;
                }
                if (!args.equals(result.message)) {
                    event.setMessage(parts[0] + " " + result.message);
                }
            } else {
                // 3. Для всех остальных команд (например /tp)
                // Игнорируем грамматику и капс, проверяем только на мат и рекламу (используем ContentType.ITEM как обход)
                String processedArgs = acf.getFilterEngine().processContent(event.getPlayer(), args, FilterEngine.ContentType.ITEM);
                if (!args.equals(processedArgs)) {
                    event.setMessage(parts[0] + " " + processedArgs);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSignChange(SignChangeEvent event) {
        String[] lines = event.getLines();
        for (int i = 0; i < lines.length; i++) {
            if (lines[i] == null || lines[i].isEmpty()) continue;

            String processed = acf.getFilterEngine().processContent(event.getPlayer(), lines[i], FilterEngine.ContentType.SIGN);
            if (!lines[i].equals(processed)) {
                event.setLine(i, processed);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBookEdit(PlayerEditBookEvent event) {
        BookMeta meta = event.getNewBookMeta();
        boolean changed = false;

        if (meta.hasPages()) {
            List<String> newPages = new ArrayList<>();
            for (String page : meta.getPages()) {
                String processed = acf.getFilterEngine().processContent(event.getPlayer(), page, FilterEngine.ContentType.BOOK);
                if (!page.equals(processed)) changed = true;
                newPages.add(processed);
            }
            if (changed) meta.setPages(newPages);
        }

        if (event.isSigning() && meta.hasTitle()) {
            String originalTitle = meta.getTitle();
            if (originalTitle != null) {
                String processedTitle = acf.getFilterEngine().processContent(event.getPlayer(), originalTitle, FilterEngine.ContentType.BOOK);
                if (!originalTitle.equals(processedTitle)) {
                    meta.setTitle(processedTitle);
                    changed = true;
                }
            }
        }

        if (changed) {
            event.setNewBookMeta(meta);
            Bukkit.getScheduler().runTask(acf, () -> {
                if (event.getPlayer().isOnline()) {
                    event.getPlayer().updateInventory();
                }
            });
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAnvilRename(PrepareAnvilEvent event) {
        ItemStack resultItem = event.getResult();
        if (resultItem == null || !resultItem.hasItemMeta()) return;

        ItemMeta meta = resultItem.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            Component displayName = meta.displayName();
            if (displayName != null) {
                String originalName = PlainTextComponentSerializer.plainText().serialize(displayName);
                Player player = (Player) event.getView().getPlayer();

                String processed = acf.getFilterEngine().processContent(player, originalName, FilterEngine.ContentType.ITEM);

                if (!originalName.equals(processed)) {
                    meta.displayName(Component.text(processed));
                    resultItem.setItemMeta(meta);
                    event.setResult(resultItem);

                    Bukkit.getScheduler().runTask(acf, () -> {
                        if (player.isOnline()) {
                            player.updateInventory();
                        }
                    });
                }
            }
        }
    }
}