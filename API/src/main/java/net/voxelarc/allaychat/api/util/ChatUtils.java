package net.voxelarc.allaychat.api.util;

import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.voxelarc.allaychat.api.AllayChat;
import org.bukkit.entity.Player;

import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class ChatUtils {

    private static final Pattern HEX_COLOR_CODE_X_PATTERN = Pattern.compile("&x(?:&[0-9a-fA-F]){6}");
    private static final Pattern HEX_COLOR_CODE_HASH_PATTERN = Pattern.compile("&#[0-9a-fA-F]{6}");

    private static final Pattern LEGACY_COLOR_CODE_PATTERN = Pattern.compile("[&§][0-9a-fk-or]");

    public final static MiniMessage MINI_MESSAGE = MiniMessage.builder()
            .tags(TagResolver.standard())
            .tags(TagResolver.resolver("papi", (argumentQueue, context) -> {
                final String papiPlaceholder = argumentQueue.popOr("papi tag requires an argument").value();
                final Player player = context.target() instanceof Player ? (Player) context.target() : null;
                final String parsedPlaceholder = PlaceholderAPI.setPlaceholders(player, '%' + papiPlaceholder + '%');
                final Component componentPlaceholder = LegacyComponentSerializer.legacyAmpersand().deserialize(parsedPlaceholder);

                return Tag.selfClosingInserting(componentPlaceholder);
            }))
            .preProcessor(s -> s.replace("<prefix>", AllayChat.getPlugin(AllayChat.class).getMessagesConfig().getString("messages.prefix", "")))
            .postProcessor(component -> component.decoration(TextDecoration.ITALIC, false))
            .build();

    public static final DecimalFormat FORMATTER = (DecimalFormat) NumberFormat.getNumberInstance();

    public static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder().character('&').hexColors().build();
    public static final LegacyComponentSerializer LEGACY_AMPERSAND = LegacyComponentSerializer.legacySection();

    public static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss");

    static {
        FORMATTER.setMinimumIntegerDigits(1);
        FORMATTER.setMaximumIntegerDigits(20);
        FORMATTER.setMaximumFractionDigits(2);
        FORMATTER.setGroupingSize(3);
    }

    public static Component format(String string, TagResolver... placeholders) {
        return MINI_MESSAGE.deserialize(string, placeholders);
    }

    public static List<Component> format(List<String> list, TagResolver... placeholders) {
        return list.stream().map(s -> format(s, placeholders)).collect(Collectors.toList());
    }

    public static void sendMessage(Audience player, Component component) {
        player.sendMessage(component);
    }

    public static void sendMessage(Audience player, List<Component> components) {
        components.forEach(s -> ChatUtils.sendMessage(player, s));
    }

    public static String removeColorCodes(String text) {
        Matcher hexXMatcher = HEX_COLOR_CODE_X_PATTERN.matcher(text);
        text = hexXMatcher.replaceAll("");

        Matcher hexHashMatcher = HEX_COLOR_CODE_HASH_PATTERN.matcher(text);
        text = hexHashMatcher.replaceAll("");

        Matcher legacyMatcher = LEGACY_COLOR_CODE_PATTERN.matcher(text);
        text = legacyMatcher.replaceAll("");

        return text;
    }

    public static TagResolver papiTag(Player player) {
        return TagResolver.resolver("papi", (argumentQueue, context) -> {
            final String papiPlaceholder = argumentQueue.popOr("papi tag requires an argument").value();
            final String parsedPlaceholder = PlaceholderAPI.setPlaceholders(player, '%' + papiPlaceholder + '%');
            final Component componentPlaceholder = LegacyComponentSerializer.legacyAmpersand().deserialize(parsedPlaceholder);

            return Tag.selfClosingInserting(componentPlaceholder);
        });
    }

}