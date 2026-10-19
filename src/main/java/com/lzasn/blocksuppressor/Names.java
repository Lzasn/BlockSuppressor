package com.lzasn.blocksuppressor;

import org.bukkit.block.Container;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/** 铁砧重命名匹配：忽略大小写、忽略各种空格。 */
final class Names {

    private Names() {
    }

    static boolean matches(BlockSuppressorPlugin plugin, Container container) {
        return matches(plugin, container.customName());
    }

    static boolean matches(BlockSuppressorPlugin plugin, Component name) {
        if (name == null) {
            return false;
        }
        String plain = PlainTextComponentSerializer.plainText().serialize(name);
        return plugin.suppressorNames().contains(normalize(plain));
    }

    /**
     * 去掉所有空白（含全角空格 / 不换行空格 / 零宽空格）并转小写。
     * 这样 "Block Suppressor"、"blocksuppressor"、"Block  Suppressor" 会归一成同一个键。
     */
    static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isWhitespace(c) || Character.isSpaceChar(c)
                    || c == '\u200B' || c == '\uFEFF') {
                continue;
            }
            builder.append(Character.toLowerCase(c));
        }
        return builder.toString();
    }
}
