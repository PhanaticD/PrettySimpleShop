package com.robomwm.prettysimpleshop.shop;

import com.robomwm.prettysimpleshop.ConfigManager;
import com.robomwm.prettysimpleshop.PrettySimpleShop;
import com.robomwm.prettysimpleshop.ReflectionHandler;
import com.robomwm.prettysimpleshop.event.ShopBoughtEvent;
import com.robomwm.prettysimpleshop.event.ShopPricedEvent;
import com.robomwm.prettysimpleshop.event.ShopViewEvent;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Created on 2/8/2018.
 *
 * @author RoboMWM
 */
public class ShopListener implements Listener
{
    private JavaPlugin instance;
    private ShopAPI shopAPI;
    private Economy economy;
    private Map<Player, ShopInfo> selectedShop = new HashMap<>();
    private Map<Player, Double> priceSetter = new HashMap<>();
    private ConfigManager config;

    private Method asNMSCopy; //CraftItemStack#asNMSCopy(ItemStack);
    private Method saveNMSItemStack; //n.m.s.ItemStack#save(compound);
    private Class<?> NBTTagCompoundClazz; //n.m.s.NBTTagCompound;

    public ShopListener(JavaPlugin plugin, ShopAPI shopAPI, Economy economy, ConfigManager configManager)
    {
        instance = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        this.shopAPI = shopAPI;
        this.config = configManager;
        this.economy = economy;

        try
        {
            asNMSCopy = ReflectionHandler.getMethod("CraftItemStack", ReflectionHandler.PackageType.CRAFTBUKKIT_INVENTORY, "asNMSCopy", ItemStack.class);
            NBTTagCompoundClazz = ReflectionHandler.PackageType.MINECRAFT_SERVER.getClass("NBTTagCompound");
            saveNMSItemStack = ReflectionHandler.getMethod("ItemStack", ReflectionHandler.PackageType.MINECRAFT_SERVER, "save", NBTTagCompoundClazz);
        }
        catch (Exception e)
        {
            instance.getLogger().warning("Reflection failed, will use legacy, non-hoverable, boring text.");
            e.printStackTrace();
        }
    }

    @EventHandler
    private void cleanup(PlayerQuitEvent event)
    {
        selectedShop.remove(event.getPlayer());
    }

    private boolean isEnabledWorld(World world)
    {
        return config.isWhitelistedWorld(world);
    }

    //We don't watch BlockDamageEvent as player may be in adventure (but uh this event probably doesn't fire in adventure either so... uhm yea... hmmm.
    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    private void onLeftClickChest(PlayerInteractEvent event)
    {
        Player player = event.getPlayer();
        if (event.getAction() != Action.LEFT_CLICK_BLOCK || player.isSneaking())
            return;
        if (!isEnabledWorld(event.getPlayer().getWorld()))
            return;

        Block block = event.getClickedBlock();
        if (block.getType() != Material.CHEST)
            return;

        Chest chest = (Chest)block.getState();
        if (!shopAPI.isShop(chest))
            return;
        ItemStack item = shopAPI.getItemStack(chest);
        double price = shopAPI.getPrice(chest);

        if (price < 0)
        {
            selectedShop.put(player, new ShopInfo(block.getLocation(), item, price));
            player.sendMessage("Shop selected.");
            return;
        }
        else if (item == null)
        {
            player.sendMessage("This shop is out of stock!");
            return;
        }

        ShopInfo shopInfo = new ShopInfo(block.getLocation(), item, price);
        selectedShop.put(player, shopInfo);

        //TODO: fire event for custom plugins (e.g. anvil GUI, if we can manage to stick itemstack in it)
        ShopViewEvent shopViewEvent = new ShopViewEvent(player, shopInfo);
        instance.getServer().getPluginManager().callEvent(shopViewEvent);
        if (shopViewEvent.isCancelled())
            return;

        String textToSend = config.getString("saleInfo", PrettySimpleShop.getItemName(item), economy.format(price), Integer.toString(item.getAmount()));
        String json;
        try
        {
            Object nmsItemStack = asNMSCopy.invoke(null, item); //CraftItemStack#asNMSCopy(itemStack); //nms version of the ItemStack
            Object nbtTagCompound = NBTTagCompoundClazz.newInstance(); //new NBTTagCompoundClazz(); //get a new NBTTagCompound, which will contain the nmsItemStack.
            nbtTagCompound = saveNMSItemStack.invoke(nmsItemStack, nbtTagCompound); //nmsItemStack#save(nbtTagCompound); //saves nmsItemStack into our new NBTTagCompound
            json = nbtTagCompound.toString();
        }
        catch (Throwable rock)
        {
            player.sendMessage(textToSend);
            return;
        }

        BaseComponent[] hoverEventComponents = new BaseComponent[]
                {
                        new TextComponent(json)
                };
        HoverEvent hover = new HoverEvent(HoverEvent.Action.SHOW_ITEM, hoverEventComponents);
        TextComponent text = new TextComponent(textToSend);
        text.setHoverEvent(hover);
        player.sendMessage(text);
        config.sendTip(player, "saleInfo");
    }

    //Collect revenues
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    private void onOpenInventory(InventoryOpenEvent event)
    {
        if (event.getPlayer().getType() != EntityType.PLAYER)
            return;
        if (event.getInventory().getLocation() == null)
            return;
        if (!(event.getInventory().getHolder() instanceof Chest || event.getInventory().getHolder() instanceof DoubleChest))
            return;
        Player player = (Player)event.getPlayer();
        Chest chest = shopAPI.getChest(event.getInventory().getLocation());

        if (priceSetter.containsKey(player) && shopAPI.isShop(chest))
        {
            double newPrice = priceSetter.remove(player);
            shopAPI.setPrice(chest, newPrice);
            player.sendMessage("Price updated to " + economy.format(newPrice));
            instance.getServer().getPluginManager().callEvent(new ShopPricedEvent(player, chest.getLocation(), newPrice));
        }

        double deposit = shopAPI.getRevenue(chest, true);
        if (deposit <= 0)
            return;
        economy.depositPlayer(player, deposit);
        player.sendMessage("Collected " + economy.format(deposit) + " in sales from this shop.");
    }
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    private void onBreakShop(BlockBreakEvent event)
    {
        Block block = event.getBlock();
        if (block.getType() != Material.CHEST)
            return;
        Chest chest = (Chest)block.getState();
        if (!shopAPI.isShop(chest))
            return;
        double deposit = shopAPI.getRevenue(chest, true);
        if (deposit <= 0)
            return;
        Player player = event.getPlayer();
        economy.depositPlayer(player, deposit);
        player.sendMessage("Collected " + economy.format(deposit) + " in sales from this shop.");
    }

    //Commands cuz well all the data's here so yea
    public boolean buyCommand(Player player, int amount)
    {
        ShopInfo shopInfo = selectedShop.remove(player);
        if (shopInfo == null)
        {
            player.sendMessage("Select a shop via left-clicking its chest.");
            return false;
        }

        if (shopInfo.getPrice() < 0)
        {
            player.sendMessage("This shop is not open for sale yet! If you are the owner, use /price <price> to set the price per item.");
            return false;
        }

        if (economy.getBalance(player) < amount * shopInfo.getPrice())
        {
            player.sendMessage("Transaction canceled: Insufficient /money. Try again with a smaller quantity?");
            return false;
        }

        shopInfo.getItem().setAmount(amount);

        if (!hasInventorySpace(player, shopInfo.getItem()))
        {
            player.sendMessage("Transaction canceled: Insufficient inventory space. Free up some inventory slots or try again with a smaller quantity.");
            return false;
        }

        ItemStack itemStack = shopAPI.performTransaction(shopAPI.getChest(shopInfo.getLocation()), shopInfo.getItem(), shopInfo.getPrice());
        if (itemStack == null)
        {
            player.sendMessage("Transaction canceled: Shop was modified. Please try again.");
            return false;
        }

        economy.withdrawPlayer(player, itemStack.getAmount() * shopInfo.getPrice());

        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(itemStack);

        player.sendMessage("Transaction completed. Bought " + itemStack.getAmount() + " " + itemStack.getType().name() + " for " + economy.format(itemStack.getAmount() * shopInfo.getPrice()));

        instance.getServer().getPluginManager().callEvent(new ShopBoughtEvent(player, shopInfo));

        if (!leftovers.isEmpty())
        {
            player.sendMessage("Somehow you bought more than you can hold and we didn't detect this. Please report this issue with the following debug info:");
            for (ItemStack itemStack1 : leftovers.values())
                player.sendMessage(itemStack1.toString());
            return true;
        }
        return true;
    }
    public void priceCommand(Player player, double price)
    {

        selectedShop.remove(player);
        priceSetter.put(player, price);
        player.sendMessage("Open the shop to apply this new price.");
    }

    //https://www.spigotmc.org/threads/detecting-when-a-players-inventory-is-almost-full.132061/#post-1401285
    public boolean hasInventorySpace(Player p, ItemStack item) {
        int free = 0;
        for (ItemStack i : p.getInventory()) {
            if (i == null) {
                free += item.getMaxStackSize();
            } else if (i.isSimilar(item)) {
                free += item.getMaxStackSize() - i.getAmount();
            }
        }
        return free >= item.getAmount();
    }
}
