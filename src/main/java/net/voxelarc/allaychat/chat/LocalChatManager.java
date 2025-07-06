package net.voxelarc.allaychat.chat;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.voxelarc.allaychat.AllayChatPlugin;
import net.voxelarc.allaychat.api.chat.ChatFormat;
import net.voxelarc.allaychat.api.chat.ChatManager;
import net.voxelarc.allaychat.api.config.YamlConfig;
import net.voxelarc.allaychat.api.filter.ChatFilter;
import net.voxelarc.allaychat.api.inventory.impl.AllayInventory;
import net.voxelarc.allaychat.api.user.ChatUser;
import net.voxelarc.allaychat.api.util.ChatUtils;
import org.bukkit.Bukkit;
import org.bukkit.Tag;
import org.bukkit.block.ShulkerBox;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;
import java.util.concurrent.TimeUnit;

@RequiredArgsConstructor
public class LocalChatManager implements ChatManager {

    private final AllayChatPlugin plugin;

    private final Map<String, ChatFormat> groupFormatMap = new LinkedHashMap<>();

    private final Map<String, Component> placeholders = new HashMap<>();
    private final Map<String, String> perPlayerPlaceholders = new HashMap<>();

    private final Cache<UUID, Inventory> inventories = CacheBuilder.newBuilder().expireAfterWrite(3, TimeUnit.MINUTES).build();
    private final Cache<String, String> lastMessageCache = CacheBuilder.newBuilder().expireAfterWrite(5, TimeUnit.MINUTES).build();

    @Getter private ChatRenderer.ViewerUnaware chatRenderer;

    @Override
    public void onEnable() {
        for (String group : plugin.getFormatConfig().getConfigurationSection("format").getKeys(false)) {
            ConfigurationSection section = plugin.getFormatConfig().getConfigurationSection("format." + group);
            String format = section.getString("format");
            ConfigurationSection hoverSection = section.getConfigurationSection("hover");
            ConfigurationSection clickSection = section.getConfigurationSection("click");

            ChatFormat.Hover hover = null;
            if (hoverSection != null && hoverSection.getBoolean("enabled", false)) {
                hover = new ChatFormat.Hover(hoverSection.getStringList("message"));
            }

            ChatFormat.Click click = null;
            if (clickSection != null && clickSection.getBoolean("enabled", false)) {
                String action = clickSection.getString("action");
                String command = clickSection.getString("command");

                ClickEvent.Action clickAction = ClickEvent.Action.valueOf(action.toUpperCase());
                click = new ChatFormat.Click(clickAction, command);
            }

            groupFormatMap.put(group, new ChatFormat(group, format, hover, click));
        }

        if (groupFormatMap.get("default") == null) {
            plugin.getLogger().warning("No default chat format found! Using the first defined format as default.");
            plugin.getLogger().warning("Please define a default format in the config.yml ASAP.");
            groupFormatMap.put("default", groupFormatMap.values().iterator().next());
        }

        if (plugin.getReplacementConfig().getBoolean("placeholder.enabled")) {
            ConfigurationSection placeholderSection = plugin.getReplacementConfig().getConfigurationSection("placeholder.placeholders");
            for (String key : placeholderSection.getKeys(false)) {
                this.placeholders.put(key, ChatUtils.format(placeholderSection.getString(key)));
            }

            ConfigurationSection perPlayerSection = plugin.getReplacementConfig().getConfigurationSection("placeholder.per-player");
            for (String key : perPlayerSection.getKeys(false)) {
                this.perPlayerPlaceholders.put(key, perPlayerSection.getString(key));
            }
        }

        this.chatRenderer = new AllayChatRenderer(this.plugin);

        plugin.getLogger().info("Loaded " + groupFormatMap.size() + " chat formats.");
    }

    public String getPlayerGroup(Player player) {
        User user = LuckPermsProvider.get().getUserManager().getUser(player.getUniqueId());
        if (user == null) return "default";

        return user.getPrimaryGroup();
    }

    @Override
    public Component formatMessage(Player player, Component message) {
        String groupName = getPlayerGroup(player);
        ChatFormat format = groupFormatMap.get(groupName);
        if (format == null) {
            format = groupFormatMap.get("default");
        }

        Component component = ChatUtils.format(
                format.format(),
                ChatUtils.papiTag(player),
                Placeholder.component("message", message)
        );

        if (format.hover() != null) {
            List<Component> hoverComponents = new ArrayList<>();
            for (String line : format.hover().message())
                hoverComponents.add(ChatUtils.format(line, ChatUtils.papiTag(player)));

            component = component.hoverEvent(
                    Component.join(JoinConfiguration.newlines(), hoverComponents)
            );
        }

        if (format.click() != null) {
            String command = format.click().command();
            command = PlaceholderAPI.setPlaceholders(player, command);
            component = component.clickEvent(ClickEvent.clickEvent(format.click().action(), command));
        }

        return component;
    }

    private Component handleChatMessage(Component messageComponent, String messageContent, Player player) {
        if (plugin.getReplacementConfig().getBoolean("color.enabled", true)) {
            if (player.hasPermission("allaychat.chatcolor")) {
                messageComponent = LegacyComponentSerializer.legacyAmpersand().deserialize(messageContent);
            } else {
                messageComponent = Component.text(messageContent);
            }
        }

        if (plugin.getReplacementConfig().getBoolean("mention.enabled"))
            messageComponent = handleMentions(messageContent, messageComponent);

        if (plugin.getReplacementConfig().getBoolean("placeholder.enabled")) {
            messageComponent = handlePlaceholders(player, messageComponent);
            // Replace the message content with the new one
            messageContent = PlainTextComponentSerializer.plainText().serialize(messageComponent);
        }

        if (plugin.getReplacementConfig().getBoolean("item.enabled"))
            messageComponent = handleItem(player, messageContent, messageComponent);

        if (plugin.getReplacementConfig().getBoolean("enderchest.enabled"))
            messageComponent = handleEnderChest(player, messageContent, messageComponent);

        if (plugin.getReplacementConfig().getBoolean("inventory.enabled"))
            messageComponent = handleInventory(player, messageContent, messageComponent);

        if (plugin.getReplacementConfig().getBoolean("shulker.enabled"))
            messageComponent = handleShulker(player, messageContent, messageComponent);

        return messageComponent;
    }

    @Override
    public boolean handleMessage(Player player, String message) {
        for (ChatFilter filter : plugin.getFilters()) {
            boolean cancel = filter.checkMessage(player, message);
            if (cancel) {
                Component component = ChatUtils.format(
                        plugin.getMessagesConfig().getString("messages.message-cancelled-operator"),
                        Placeholder.unparsed("player", player.getName()),
                        Placeholder.unparsed("message", ChatUtils.removeColorCodes(message)),
                        Placeholder.unparsed("flag", filter.getClass().getSimpleName()
                                .replace("Filter", "").toUpperCase())
                );
                plugin.getPlayerManager().broadcast(component, "allaychat.staff");
                return true;
            }
        }

        return false;
    }

    @Override
    public void handleChatEvent(AsyncChatEvent event) {
        String message = PlainTextComponentSerializer.plainText().serialize(event.message());
        event.setCancelled(plugin.getChatManager().handleMessage(event.getPlayer(), message));

        // Handle the message (and replacements) separately so we can add an option to disable chat formatting
        event.message(handleChatMessage(event.message(), message, event.getPlayer()));

        if (plugin.getFormatConfig().getBoolean("enabled", true))
            event.renderer(ChatRenderer.viewerUnaware(plugin.getChatManager().getChatRenderer()));

        event.viewers().removeIf(viewer -> {
            if (!(viewer instanceof Player player)) return false;
            ChatUser user = plugin.getUserManager().getUser(player.getUniqueId());
            // Data not loaded yet
            if (user == null) return false;

            return user.getIgnoredPlayers().contains(event.getPlayer().getName());
        });
    }

    @Override
    public boolean handlePrivateMessage(Player from, String to, String message) {
        ChatUser user = plugin.getUserManager().getUser(from.getUniqueId());
        if (user == null) {
            ChatUtils.sendMessage(from, ChatUtils.format(
                    plugin.getMessagesConfig().getString("messages.data-not-loaded")
            ));
            return false;
        }

        Player target = Bukkit.getPlayerExact(to);
        if (target == null) {
            ChatUtils.sendMessage(from, ChatUtils.format(
                    plugin.getPrivateMessageConfig().getString("messages.not-found")
            ));
            return false;
        }

        ChatUser targetUser = plugin.getUserManager().getUser(target.getUniqueId());
        if (targetUser == null) {
            ChatUtils.sendMessage(from, ChatUtils.format(
                    plugin.getMessagesConfig().getString("messages.data-not-loaded")
            ));
            return false;
        }

        if (from.getName().equalsIgnoreCase(target.getName())) {
            ChatUtils.sendMessage(from, ChatUtils.format(
                    plugin.getPrivateMessageConfig().getString("messages.self")
            ));
            return false;
        }

        if (targetUser.getIgnoredPlayers().contains(from.getName())) {
            ChatUtils.sendMessage(from, ChatUtils.format(
                    plugin.getMessagesConfig().getString("messages.ignoring-you"),
                    Placeholder.unparsed("player", target.getName())
            ));
            return false;
        }

        if (user.getIgnoredPlayers().contains(target.getName())) {
            ChatUtils.sendMessage(from, ChatUtils.format(
                    plugin.getMessagesConfig().getString("messages.ignoring"),
                    Placeholder.unparsed("player", target.getName())
            ));
            return false;
        }

        if (plugin.getPrivateMessageConfig().getBoolean("filter")
                && handleMessage(from, message)) {
            return false;
        }

        Component spyComponent = ChatUtils.format(
                plugin.getPrivateMessageConfig().getString("messages.spy"),
                Placeholder.unparsed("from", from.getName()),
                Placeholder.unparsed("to", to),
                Placeholder.unparsed("message", message)
        );

        Component msgTarget = ChatUtils.format(
                plugin.getPrivateMessageConfig().getString("messages.format-target"),
                Placeholder.unparsed("player", from.getName()),
                Placeholder.unparsed("message", message)
        );

        Component msgSender = ChatUtils.format(
                plugin.getPrivateMessageConfig().getString("messages.format-self"),
                Placeholder.unparsed("player", target.getName()),
                Placeholder.unparsed("message", message)
        );

        ChatUtils.sendMessage(target, msgTarget);
        ChatUtils.sendMessage(from, msgSender);

        this.lastMessageCache.put(from.getName(), to);
        this.lastMessageCache.put(to, from.getName());

        plugin.getUserManager().getAllUsers().stream().filter(ChatUser::isSpyEnabled).forEach(spyUser -> {
            Player player = Bukkit.getPlayer(spyUser.getUniqueId());
            if (player == null) return;

            ChatUtils.sendMessage(player, spyComponent);
        });

        return true;
    }

    @Override
    public void handleStaffChatMessage(Player from, String message) {
        Component component = ChatUtils.format(
                plugin.getStaffChatConfig().getString("format"),
                ChatUtils.papiTag(from),
                Placeholder.unparsed("message", message),
                Placeholder.unparsed("player", from.getName())
        );

        plugin.getPlayerManager().broadcast(component, "allaychat.staff");
    }

    @Override
    public String getLastMessagedPlayer(String playerName) {
        return this.lastMessageCache.getIfPresent(playerName);
    }

    @Override
    public Inventory getInventory(UUID id) {
        return this.inventories.getIfPresent(id);
    }

    @Override
    public void setInventory(UUID id, String playerName, Inventory inventory, InventoryType type) {
        int size = switch (type) {
            case SHULKER -> 27;
            case INVENTORY -> 45;
            case ENDER_CHEST -> inventory.getSize();
        };

        String titleString = switch (type) {
            case SHULKER -> plugin.getReplacementConfig().getString("shulker.gui-title");
            case INVENTORY -> plugin.getReplacementConfig().getString("inventory.gui-title");
            case ENDER_CHEST -> plugin.getReplacementConfig().getString("enderchest.gui-title");
        };

        Component title = ChatUtils.format(titleString, Placeholder.unparsed("player", playerName));
        this.inventories.put(id, new AllayInventory(inventory.getContents(), title, size).getInventory());
    }

    private Component handleMentions(String messageContent, Component messageComponent) {
        YamlConfig replacementConfig = plugin.getReplacementConfig();
        if (replacementConfig.getBoolean("mention.enabled")) {
            for (String playerName : plugin.getPlayerManager().getAllPlayers()) {
                if (messageContent.contains(playerName)) {
                    String soundName = replacementConfig.getString("mention.sound");
                    if (soundName != null && !soundName.isEmpty()) {
                        Sound sound = Sound.sound(Key.key(soundName), Sound.Source.MASTER, 1.0f, 1.0f);
                        plugin.getPlayerManager().playSound(playerName, sound);
                    }

                    if (replacementConfig.getBoolean("mention.title.enabled")) {
                        String titleText = replacementConfig.getString("mention.title.title");
                        String subtitleText = replacementConfig.getString("mention.title.subtitle");
                        Title title = Title.title(
                                ChatUtils.format(titleText, Placeholder.unparsed("player", playerName)),
                                ChatUtils.format(subtitleText, Placeholder.unparsed("player", playerName))
                        );
                        plugin.getPlayerManager().showTitle(playerName, title);
                    }

                    String actionBar = replacementConfig.getString("mention.actionbar");
                    if (actionBar != null && !actionBar.isEmpty()) {
                        Component actionBarComponent = ChatUtils.format(actionBar, Placeholder.unparsed("player", playerName));
                        plugin.getPlayerManager().showActionBar(playerName, actionBarComponent);
                    }

                    String mentionMessage = replacementConfig.getString("mention.message");
                    if (mentionMessage != null && !mentionMessage.isEmpty()) {
                        Component mentionMessageComponent = ChatUtils.format(mentionMessage, Placeholder.unparsed("player", playerName));
                        plugin.getPlayerManager().sendMessage(playerName, mentionMessageComponent);
                    }

                    messageComponent = messageComponent.replaceText(TextReplacementConfig.builder()
                            .matchLiteral(playerName)
                            .replacement(ChatUtils.format(replacementConfig.getString("mention.text"), Placeholder.unparsed("player", playerName)))
                            .build()
                    );
                }
            }
        }

        return messageComponent;
    }

    private Component handlePlaceholders(Player player, Component messageComponent) {
        for (Map.Entry<String, Component> entry : this.placeholders.entrySet()) {
            messageComponent = messageComponent.replaceText(TextReplacementConfig.builder()
                    .matchLiteral(entry.getKey())
                    .replacement(entry.getValue())
                    .build()
            );
        }

        for (Map.Entry<String, String> entry : this.perPlayerPlaceholders.entrySet()) {
            messageComponent = messageComponent.replaceText(TextReplacementConfig.builder()
                    .matchLiteral(entry.getKey())
                    .replacement(ChatUtils.format(entry.getValue(), ChatUtils.papiTag(player)))
                    .build()
            );
        }

        return messageComponent;
    }

    private Component handleItem(Player player, String messageContent, Component messageComponent) {
        String syntax = plugin.getReplacementConfig().getString("item.syntax", "[item]");
        if (!messageContent.contains(syntax)) return messageComponent;

        ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null || item.isEmpty()) return messageComponent;

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return messageComponent;

        Component component = ChatUtils.format(
                plugin.getReplacementConfig().getString("item.text"),
                Placeholder.unparsed("amount", item.getAmount() + ""),
                Placeholder.component("item", (meta.hasDisplayName() ? meta.displayName() : Component.translatable(item)).hoverEvent(item))
        );

        messageComponent = messageComponent.replaceText(TextReplacementConfig.builder()
                .matchLiteral(syntax)
                .replacement(component.hoverEvent(item))
                .build()
        );

        return messageComponent;
    }

    private Component handleInventory(Player player, String messageContent, Component messageComponent) {
        String syntax = plugin.getReplacementConfig().getString("inventory.syntax", "[inventory]");
        if (messageContent.contains(syntax)) {
            Component component = ChatUtils.format(plugin.getReplacementConfig().getString("inventory.text"), Placeholder.unparsed("player", player.getName()));

            UUID uuid = UUID.randomUUID();
            plugin.getChatManager().setInventory(uuid, player.getName(), player.getInventory(), InventoryType.INVENTORY);

            component = component.clickEvent(ClickEvent.clickEvent(ClickEvent.Action.RUN_COMMAND, "/allay inventory %s".formatted(uuid)));
            component = component.hoverEvent(ChatUtils.format(
                    plugin.getReplacementConfig().getString("inventory.hover"),
                    Placeholder.unparsed("player", player.getName())
            ));

            messageComponent = messageComponent.replaceText(TextReplacementConfig.builder()
                    .matchLiteral(syntax)
                    .replacement(component)
                    .build()
            );
        }

        return messageComponent;
    }

    private Component handleEnderChest(Player player, String messageContent, Component messageComponent) {
        String syntax = plugin.getReplacementConfig().getString("enderchest.syntax", "[enderchest]");
        if (!messageContent.contains(syntax)) return messageComponent;

        Component component = ChatUtils.format(plugin.getReplacementConfig().getString("enderchest.text"), Placeholder.unparsed("player", player.getName()));

        UUID uuid = UUID.randomUUID();
        plugin.getChatManager().setInventory(uuid, player.getName(), player.getEnderChest(), InventoryType.ENDER_CHEST);

        component = component.clickEvent(ClickEvent.clickEvent(ClickEvent.Action.RUN_COMMAND, "/allay inventory %s".formatted(uuid)));
        component = component.hoverEvent(ChatUtils.format(
                plugin.getReplacementConfig().getString("enderchest.hover"),
                Placeholder.unparsed("player", player.getName())
        ));

        messageComponent = messageComponent.replaceText(TextReplacementConfig.builder()
                .matchLiteral(syntax)
                .replacement(component)
                .build()
        );

        return messageComponent;
    }

    private Component handleShulker(Player player, String messageContent, Component messageComponent) {
        String syntax = plugin.getReplacementConfig().getString("shulker.syntax", "[shulker]");
        if (!messageContent.contains(syntax)) return messageComponent;

        ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null || item.isEmpty()) return messageComponent;

        if (Tag.SHULKER_BOXES.isTagged(item.getType())) {
            BlockStateMeta meta = (BlockStateMeta) item.getItemMeta();
            ShulkerBox shulkerBox = (ShulkerBox) meta.getBlockState();

            UUID uuid = UUID.randomUUID();
            plugin.getChatManager().setInventory(uuid, player.getName(), shulkerBox.getInventory(), InventoryType.SHULKER);

            Component component = ChatUtils.format(plugin.getReplacementConfig().getString("shulker.text"), Placeholder.unparsed("player", player.getName()));

            component = component.clickEvent(ClickEvent.clickEvent(ClickEvent.Action.RUN_COMMAND, "/allay inventory %s".formatted(uuid)));
            component = component.hoverEvent(ChatUtils.format(
                    plugin.getReplacementConfig().getString("enderchest.hover"),
                    Placeholder.unparsed("player", player.getName())
            ));

            messageComponent = messageComponent.replaceText(TextReplacementConfig.builder()
                    .matchLiteral(syntax)
                    .replacement(component)
                    .build()
            );
        }

        return messageComponent;
    }

}
