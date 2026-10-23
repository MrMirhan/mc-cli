package dev.mccli.util;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.ItemLore;

import java.util.List;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Holder;

/**
 * Utility for serializing ItemStack data to JSON.
 */
public final class ItemJson {
    private static final Gson GSON = new Gson();

    private ItemJson() {}

    public static JsonObject fromStack(ItemStack stack, boolean includeNbt) {
        JsonObject item = new JsonObject();

        if (stack == null || stack.isEmpty()) {
            item.addProperty("empty", true);
            return item;
        }

        item.addProperty("empty", false);
        item.addProperty("id", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        item.addProperty("name", stack.getHoverName().getString());
        item.addProperty("count", stack.getCount());
        item.addProperty("max_count", stack.getMaxStackSize());

        if (stack.isDamageableItem()) {
            item.addProperty("damage", stack.getDamageValue());
            item.addProperty("max_damage", stack.getMaxDamage());
            item.addProperty("durability", stack.getMaxDamage() - stack.getDamageValue());
        }

        item.addProperty("rarity", stack.getRarity().getSerializedName());
        Identifier model = stack.get(DataComponents.ITEM_MODEL);
        if (model != null) {
            item.addProperty("item_model", model.toString());
        }

        CustomModelData customModelData = stack.get(DataComponents.CUSTOM_MODEL_DATA);
        if (customModelData != null) {
            JsonObject cmd = new JsonObject();
            cmd.add("floats", GSON.toJsonTree(customModelData.floats()));
            cmd.add("flags", GSON.toJsonTree(customModelData.flags()));
            cmd.add("strings", GSON.toJsonTree(customModelData.strings()));
            cmd.add("colors", GSON.toJsonTree(customModelData.colors()));
            item.add("custom_model_data", cmd);
        }

        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore != null && !lore.lines().isEmpty()) {
            item.add("lore", lines(lore.lines()));
        }

        // Include full component string if requested
        if (includeNbt) {
            item.addProperty("components", stack.getComponents().toString());
        }

        // Enchantments
        ItemEnchantments enchantments = stack.get(DataComponents.ENCHANTMENTS);
        if (enchantments != null && !enchantments.isEmpty()) {
            JsonArray enchantmentsArray = new JsonArray();
            for (Holder<Enchantment> entry : enchantments.keySet()) {
                JsonObject enchant = new JsonObject();
                // Handle both Reference (has ID) and Direct (no ID) registry entries
                String enchantId = entry.getRegisteredName();
                enchant.addProperty("id", enchantId);
                enchant.addProperty("level", enchantments.getLevel(entry));
                enchantmentsArray.add(enchant);
            }
            item.add("enchantments", enchantmentsArray);
        }

        return item;
    }

    /** {@link #fromStack} plus the tooltip exactly as this client would draw it (name, lore, enchantments, attributes). */
    public static JsonObject withTooltip(ItemStack stack) {
        JsonObject item = fromStack(stack, false);
        Minecraft client = Minecraft.getInstance();
        if (stack != null && !stack.isEmpty() && client.level != null) {
            List<Component> tooltip = stack.getTooltipLines(Item.TooltipContext.of(client.level), client.player,
                client.options.advancedItemTooltips ? TooltipFlag.ADVANCED : TooltipFlag.NORMAL);
            item.add("tooltip", lines(tooltip));
        }
        return item;
    }

    /** Each line as {text, json}: plain text for reading, the component JSON for colours and fonts. */
    public static JsonArray lines(List<Component> lines) {
        JsonArray out = new JsonArray();
        Minecraft client = Minecraft.getInstance();
        DynamicOps<JsonElement> ops = client.level != null
            ? RegistryOps.create(JsonOps.INSTANCE, client.level.registryAccess()) : JsonOps.INSTANCE;
        for (Component line : lines) {
            JsonObject entry = new JsonObject();
            entry.addProperty("text", line.getString());
            ComponentSerialization.CODEC.encodeStart(ops, line).result().ifPresent(json -> entry.add("json", json));
            out.add(entry);
        }
        return out;
    }
}
