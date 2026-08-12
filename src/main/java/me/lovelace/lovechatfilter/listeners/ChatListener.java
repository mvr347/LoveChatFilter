package me.lovelace.lovechatfilter.listeners;

import me.lovelace.lovechat.Lovechat;
import me.lovelace.lovechat.api.LovechatAPI.LovechatDeleteEvent;
import me.lovelace.lovechat.api.LovechatAPI.LovechatMessageEditEvent;
import me.lovelace.lovechat.api.LovechatAPI.LovechatMessageEvent;
import me.lovelace.lovechatfilter.LoveChatFilter;
import me.lovelace.lovechatfilter.filters.FilterEngine;
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
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

@SuppressWarnings({"deprecation", "UnstableApiUsage"})
public class ChatListener implements Listener {
    private final LoveChatFilter acf;
    private Field lovechatHistoryField;

    public ChatListener(LoveChatFilter acf) {
        this.acf = acf;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        acf.getDatabaseManager().preloadGrammarEnabled(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLovechatMessage(LovechatMessageEvent event) {
        String originalText = event.getMessage();
        FilterEngine.ProcessResult result = processChatMessage(event.getPlayer(), originalText);

        if (result.cancelled) {
            event.setCancelled(true);
            return;
        }

        if (!originalText.equals(result.message)) {
            event.setMessage(result.message);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLovechatMessageEdit(LovechatMessageEditEvent event) {
        removeLovechatPacketEcho(event.getMessageId());

        String originalText = event.getNewMessage();
        FilterEngine.ProcessResult result = processEditedChatMessage(event.getPlayer(), originalText);

        if (result.cancelled) {
            event.setCancelled(true);
            return;
        }

        if (!originalText.equals(result.message)) {
            event.setNewMessage(result.message);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onLovechatDelete(LovechatDeleteEvent event) {
        removeLovechatPacketEcho(event.getMessageId());
    }

    private FilterEngine.ProcessResult processChatMessage(Player player, String originalText) {
        FilterEngine.ProcessResult result = acf.getFilterEngine().processChat(player, originalText);
        if (result.cancelled) {
            return result;
        }

        String finalText = result.message;
        if (acf.getConfigManager().isModuleEnabled("grammar-fix") && acf.getGrammarManager().isEnabled(player)) {
            finalText = acf.getGrammarManager().applyGrammar(finalText);
        }

        result.message = finalText;
        return result;
    }

    private FilterEngine.ProcessResult processEditedChatMessage(Player player, String originalText) {
        String finalText = acf.getFilterEngine().processContent(player, originalText, FilterEngine.ContentType.CHAT);
        if (acf.getConfigManager().isModuleEnabled("grammar-fix") && acf.getGrammarManager().isEnabled(player)) {
            finalText = acf.getGrammarManager().applyGrammar(finalText);
        }

        if (acf.getConfigManager().isModuleEnabled("emptymessages-clear")) {
            String clean = finalText.replaceAll("(?i)[&§][0-9a-fk-orx]", "").strip();
            if (clean.isEmpty()) {
                return new FilterEngine.ProcessResult("", true);
            }
        }

        return new FilterEngine.ProcessResult(finalText, false);
    }

    private void removeLovechatPacketEcho(int messageId) {
        try {
            Object chatHistory = getLovechatHistoryCache();
            if (chatHistory == null) return;

            var getIfPresent = chatHistory.getClass().getMethod("getIfPresent", Object.class);
            getIfPresent.setAccessible(true);

            // Use a snapshot to avoid ConcurrentModificationException if players join/leave
            List<Player> playerSnapshot = new ArrayList<>(Bukkit.getOnlinePlayers());
            for (Player player : playerSnapshot) {
                Object historyObj = getIfPresent.invoke(chatHistory, player.getUniqueId());
                if (!(historyObj instanceof List<?> history)) continue;

                synchronized (history) {
                    for (int i = 0; i < history.size(); i++) {
                        Object lineObj = history.get(i);
                        if (!(lineObj instanceof Lovechat.ChatLine line)) continue;
                        if (line.messageId() == messageId && !line.isPluginMessage()) {
                            String expectedPlain = PlainTextComponentSerializer.plainText().serialize(line.component());
                            removeFollowingEchoes(history, i + 1, expectedPlain);
                            break;
                        }
                    }
                }
            }
        } catch (ReflectiveOperationException e) {
            acf.getLogger().warning("Failed to remove lovechat packet echo (reflection error): " + e.getMessage());
        } catch (RuntimeException e) {
            acf.getLogger().severe("Unexpected error in removeLovechatPacketEcho: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private Object getLovechatHistoryCache() throws ReflectiveOperationException {
        Lovechat lovechat = Lovechat.getInstance();
        if (lovechatHistoryField == null) {
            Field field = Lovechat.class.getDeclaredField("chatHistory");
            field.setAccessible(true);
            lovechatHistoryField = field;
        }
        return lovechatHistoryField.get(lovechat);
    }

    private void removeFollowingEchoes(List<?> history, int startIndex, String expectedPlain) {
        int index = startIndex;
        while (index < history.size()) {
            Object candidateObj = history.get(index);
            if (!(candidateObj instanceof Lovechat.ChatLine candidate)) return;
            if (!candidate.isPluginMessage()) return;

            String candidatePlain = PlainTextComponentSerializer.plainText().serialize(candidate.component());
            if (!candidatePlain.equals(expectedPlain)) return;

            history.remove(index);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!isAuthenticated(event.getPlayer())) return;

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

    private boolean isAuthenticated(Player player) {
        return dev.lovelace.lovecore.api.LoveCore.service(dev.lovelace.lovecore.api.auth.AuthOracle.class)
                .map(oracle -> oracle.isAuthenticated(player.getUniqueId()))
                .orElse(true);
    }
}
