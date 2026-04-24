package io.nexstudios.nexeconomy.service.menu.bank.detail.view;

import io.nexstudios.configservice.config.ConfigurationSection;
import io.nexstudios.configservice.config.FileConfiguration;
import io.nexstudios.configservice.service.singlereader.FileReaderService;
import io.nexstudios.itemservice.bukkit.service.item.ItemService;
import io.nexstudios.menuservice.core.element.StaticMenuElement;
import io.nexstudios.menuservice.core.view.AbstractMenuView;
import io.nexstudios.nexeconomy.NexEconomyPlugin;
import io.nexstudios.nexeconomy.definition.AmountNotation;
import io.nexstudios.nexeconomy.definition.MantissaAmount;
import io.nexstudios.nexeconomy.domain.EcoPlayer;
import io.nexstudios.nexeconomy.service.bank.cache.BankAccountCacheService;
import io.nexstudios.nexeconomy.service.bank.definition.BankDefinition;
import io.nexstudios.nexeconomy.service.bank.registry.BankRegistryService;
import io.nexstudios.nexeconomy.service.menu.bank.detail.BankDetailMenuDefinition;
import io.nexstudios.nexeconomy.service.menu.bank.overview.view.BankOverviewMenuView;
import io.nexstudios.nexlogic.bukkit.services.entity.nexeconomy.BankMemberEntity;
import io.nexstudios.nexlogic.bukkit.services.items.ItemProviderService;
import io.nexstudios.serviceregistry.di.ServiceAccessor;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Static detail view for a single bank account.
 * Opened by clicking a bank entry in {@link BankOverviewMenuView}.
 * All action buttons currently send a test message; real logic is implemented later.
 */
public final class BankDetailMenuView extends AbstractMenuView {

  private static final MiniMessage MINI = MiniMessage.miniMessage();
  private static final String CONFIG_PATH = "inventories/bank-detail.yml";

  public BankDetailMenuView(ServiceAccessor accessor, String bankId, UUID ownerUuid, UUID viewerUuid) {
    super(BankDetailMenuDefinition.KEY, rowsToSize(6));

    // ── Services ─────────────────────────────────────────────────────────────
    FileReaderService fileReader      = accessor.getService(FileReaderService.class);
    ItemService itemService           = accessor.getService(ItemService.class);
    BankRegistryService bankRegistry  = accessor.getService(BankRegistryService.class);
    BankAccountCacheService bankCache = accessor.getService(BankAccountCacheService.class);
    ItemProviderService itemProvider  = NexEconomyPlugin.getNexLogicService().getService(ItemProviderService.class);

    FileConfiguration config = fileReader.load(Path.of(CONFIG_PATH), CONFIG_PATH, false);

    // ── Runtime data ─────────────────────────────────────────────────────────
    BankAccountCacheService.View view = bankCache.get(bankId, ownerUuid);
    BankDefinition def = bankRegistry.bank(bankId).orElse(null);

    // ── Title ────────────────────────────────────────────────────────────────
    setTitle(MINI.deserialize(config.getString("menu.title", "<yellow>Bank Details</yellow>")));

    // ── Background fill ──────────────────────────────────────────────────────
    ConfigurationSection fillCfg = config.getSection("items.fill");
    if (fillCfg != null) fill(buildItem(itemProvider, itemService, fillCfg, Map.of()));

    // ── Resolve role ─────────────────────────────────────────────────────────
    // Owner has all permissions; members use their assigned role definition.
    boolean isOwner = viewerUuid.equals(ownerUuid);
    BankDefinition.RoleDefinition role = resolveRole(def, view, viewerUuid, isOwner);

    // ── Level data ───────────────────────────────────────────────────────────
    int currentLevel = (view != null && view.account() != null) ? view.account().getLevel() : 1;
    if (currentLevel <= 0) currentLevel = 1;
    int maxLevel = (def != null && def.levels() != null) ? def.levels().size() : 1;
    BankDefinition.LevelDefinition levelDef = findLevel(def, currentLevel);

    String maxBalanceStr   = levelDef != null ? levelDef.maxBalanceRaw()   : (def != null ? def.defaultMaxBalanceRaw() : "∞");
    String interestRateStr = levelDef != null ? levelDef.interestRateRaw() : "0%";

    // ── Balances ─────────────────────────────────────────────────────────────
    MantissaAmount bankBalance = (view != null && view.balance() != null)
        ? view.balance() : MantissaAmount.zero();
    String balanceStr = AmountNotation.formatShort(bankBalance, 2);

    // remaining capacity (max - current); only if max is a parseable number
    String remainingStr = computeRemaining(bankBalance, maxBalanceStr);

    // wallet balance of the viewer for this bank's currency
    String walletStr = resolveWalletBalance(viewerUuid, def);

    // ── Withdraw / hourly / daily limits from role ────────────────────────────
    String hourlyLimit = "∞";
    String dailyLimit  = "∞";
    if (role != null && role.withdraw() != null) {
      BankDefinition.WithdrawDefinition wd = role.withdraw();
      if (wd.hourlyLimitRaw() != null && !wd.hourlyLimitRaw().isBlank()) hourlyLimit = wd.hourlyLimitRaw();
      if (wd.dailyLimitRaw()  != null && !wd.dailyLimitRaw().isBlank())  dailyLimit  = wd.dailyLimitRaw();
    }

    // ── Permission flags ─────────────────────────────────────────────────────
    boolean canDeposit  = isOwner || (role != null && role.canDeposit());
    boolean canWithdraw = isOwner || (role != null && role.withdraw() != null && role.withdraw().canWithdraw());
    boolean canInvite   = isOwner || (role != null && role.canInvite());
    boolean canManage   = isOwner; // member management: owner only for now
    boolean canUpgrade  = isOwner || (role != null && role.canUpgrade());
    boolean canViewLog  = isOwner || (role != null && role.canViewLog());

    // ── Role display ─────────────────────────────────────────────────────────
    String roleName = isOwner ? "Owner"
        : (role != null ? MINI.stripTags(role.nameMiniMessage()) : "Member");

    // ── Shared placeholders ──────────────────────────────────────────────────
    String bankName  = def != null ? MINI.stripTags(def.nameMiniMessage()) : bankId;
    String ownerName = resolvePlayerName(ownerUuid);
    String bankType  = def != null && def.memberSystem().enabled() ? "Shared" : "Private";

    Map<String, String> ph = new HashMap<>();
    ph.put("bank-name",                bankName);
    ph.put("bank-id",                  bankId.toLowerCase());
    ph.put("owner-name",               ownerName);
    ph.put("bank-type",                bankType);
    ph.put("role-name",                roleName);
    ph.put("bank-balance",             balanceStr);
    ph.put("max-balance",              maxBalanceStr);
    ph.put("remaining-capacity",       remainingStr);
    ph.put("hourly-used",              "N/A");   // async – filled later
    ph.put("hourly-limit",             hourlyLimit);
    ph.put("daily-used",               "N/A");   // async – filled later
    ph.put("daily-limit",              dailyLimit);
    ph.put("wallet-balance",           walletStr);
    ph.put("deposit-status",           canDeposit  ? "✔" : "✗");
    ph.put("deposit-disabled-reason",  canDeposit  ? "" : "No permission");
    ph.put("withdraw-status",          canWithdraw ? "✔" : "✗");
    ph.put("withdraw-disabled-reason", canWithdraw ? "" : "No permission");
    ph.put("invite-status",            canInvite   ? "✔" : "✗");
    ph.put("invite-disabled-reason",   canInvite   ? "" : "No permission");
    ph.put("invite-reason",            canInvite   ? "Allowed" : "No permission");
    ph.put("member-status",            canManage   ? "✔" : "✗");
    ph.put("member-disabled-reason",   canManage   ? "" : "No permission");
    ph.put("member-reason",            canManage   ? "Allowed" : "No permission");
    ph.put("log-status",               canViewLog  ? "✔" : "✗");
    ph.put("log-disabled-reason",      canViewLog  ? "" : "No permission");
    ph.put("current-level",            String.valueOf(currentLevel));
    ph.put("max-level",                String.valueOf(maxLevel));
    ph.put("interest-rate",            interestRateStr);
    ph.put("level-status",             canUpgrade  ? "✔" : "✗");
    ph.put("level-disabled-reason",    canUpgrade  ? "" : "No permission");
    ph.put("bank-lock-suffix",         "");

    // ── Slot layout ──────────────────────────────────────────────────────────
    int infoSlot        = config.getInt("layout.slots.info",         49);
    int depositSlot     = config.getInt("layout.slots.deposit",      10);
    int depositAllSlot  = config.getInt("layout.slots.deposit-all",  11);
    int withdrawSlot    = config.getInt("layout.slots.withdraw",     15);
    int withdrawAllSlot = config.getInt("layout.slots.withdraw-all", 16);
    int transSlot       = config.getInt("layout.slots.transactions", 13);
    int levelSlot       = config.getInt("layout.slots.level",        31);
    int inviteSlot      = config.getInt("layout.slots.invite",       29);
    int memberSlot      = config.getInt("layout.slots.member",       33);
    int backSlot        = config.getInt("layout.slots.back",         45);

    // ── Info (static – no click) ─────────────────────────────────────────────
    ConfigurationSection infoCfg = config.getSection("items.info");
    if (infoCfg != null) {
      ItemStack infoItem = buildItem(itemProvider, itemService, infoCfg, ph);
      if (infoItem.getItemMeta() instanceof SkullMeta skullMeta) {
        skullMeta.setOwningPlayer(Bukkit.getOfflinePlayer(ownerUuid));
        infoItem.setItemMeta(skullMeta);
      }
      addElement(infoSlot, new StaticMenuElement(infoItem, (ctx, event) -> {}));
    }

    // ── Deposit ──────────────────────────────────────────────────────────────
    addButton(config, canDeposit ? "items.deposit" : "items.deposit-disabled",
        depositSlot, itemProvider, itemService, ph, "[TEST] Deposit – " + bankId);

    // ── Deposit All ──────────────────────────────────────────────────────────
    addButton(config, canDeposit ? "items.deposit-all" : "items.deposit-all-disabled",
        depositAllSlot, itemProvider, itemService, ph, "[TEST] Deposit All – " + bankId);

    // ── Withdraw ─────────────────────────────────────────────────────────────
    addButton(config, canWithdraw ? "items.withdraw" : "items.withdraw-disabled",
        withdrawSlot, itemProvider, itemService, ph, "[TEST] Withdraw – " + bankId);

    // ── Withdraw All ─────────────────────────────────────────────────────────
    addButton(config, canWithdraw ? "items.withdraw-all" : "items.withdraw-all-disabled",
        withdrawAllSlot, itemProvider, itemService, ph, "[TEST] Withdraw All – " + bankId);

    // ── Transactions ─────────────────────────────────────────────────────────
    addButton(config, canViewLog ? "items.transactions" : "items.transactions-disabled",
        transSlot, itemProvider, itemService, ph, "[TEST] Transactions – " + bankId);

    // ── Level ────────────────────────────────────────────────────────────────
    addButton(config, canUpgrade ? "items.level" : "items.level-disabled",
        levelSlot, itemProvider, itemService, ph, "[TEST] Level – " + bankId);

    // ── Invite ───────────────────────────────────────────────────────────────
    addButton(config, canInvite ? "items.invite" : "items.invite-disabled",
        inviteSlot, itemProvider, itemService, ph, "[TEST] Invite – " + bankId);

    // ── Member ───────────────────────────────────────────────────────────────
    addButton(config, canManage ? "items.member" : "items.member-disabled",
        memberSlot, itemProvider, itemService, ph, "[TEST] Members – " + bankId);

    // ── Back ─────────────────────────────────────────────────────────────────
    ConfigurationSection backCfg = config.getSection("items.back");
    ItemStack backItem = backCfg != null
        ? buildItem(itemProvider, itemService, backCfg, Map.of())
        : new ItemStack(Material.ARROW);
    addElement(backSlot, new StaticMenuElement(backItem,
        (ctx, event) -> ctx.menuService().open(ctx.viewer(), new BankOverviewMenuView(accessor))));
  }

  // ── Role resolution ───────────────────────────────────────────────────────

  private static BankDefinition.RoleDefinition resolveRole(
      BankDefinition def, BankAccountCacheService.View view, UUID viewerUuid, boolean isOwner) {
    if (isOwner || def == null || !def.memberSystem().enabled()) return null;
    if (view == null || view.members() == null) return null;
    for (BankMemberEntity m : view.members()) {
      if (viewerUuid.equals(m.getMemberUuid())) {
        String roleId = m.getRoleIdLower();
        if (roleId != null) return def.memberSystem().rolesByIdLower().get(roleId);
      }
    }
    return null;
  }

  private static BankDefinition.LevelDefinition findLevel(BankDefinition def, int level) {
    if (def == null || def.levels() == null) return null;
    for (BankDefinition.LevelDefinition ld : def.levels()) {
      if (ld.level() == level) return ld;
    }
    // fallback: last defined level
    return def.levels().isEmpty() ? null : def.levels().get(def.levels().size() - 1);
  }

  /** Tries to subtract balance from maxBalanceRaw (if it's a plain number). */
  private static String computeRemaining(MantissaAmount balance, String maxRaw) {
    if (maxRaw == null || maxRaw.isBlank() || maxRaw.equals("∞")) return "∞";
    try {
      MantissaAmount max = MantissaAmount.of(new BigDecimal(maxRaw.replace(",", "").replace("_", "")), 0);
      MantissaAmount remaining = max.subtract(balance);
      if (remaining.compareTo(MantissaAmount.zero()) < 0) remaining = MantissaAmount.zero();
      return AmountNotation.formatShort(remaining, 2);
    } catch (Exception e) {
      return maxRaw; // raw string if not parseable
    }
  }

  /** Returns the viewer's wallet balance for this bank's currency, or "N/A" if not loaded. */
  private static String resolveWalletBalance(UUID viewerUuid, BankDefinition def) {
    if (def == null) return "N/A";
    EcoPlayer eco = EcoPlayer.of(viewerUuid);
    if (eco == null) return "N/A";
    MantissaAmount walletBal = eco.vault().balance(def.currencyIdLower());
    return AmountNotation.formatShort(walletBal, 2);
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  private void addButton(FileConfiguration config, String sectionKey, int slot,
                         ItemProviderService itemProvider, ItemService itemService,
                         Map<String, String> ph, String testMessage) {
    ConfigurationSection cfg = config.getSection(sectionKey);
    if (cfg == null) return;
    addElement(slot, new StaticMenuElement(
        buildItem(itemProvider, itemService, cfg, ph),
        (ctx, event) -> ctx.viewer().sendMessage(Component.text(testMessage))));
  }

  private static ItemStack buildItem(ItemProviderService itemProvider, ItemService itemService,
                                     ConfigurationSection section, Map<String, String> ph) {
    String itemId = section.getString("item", "minecraft:stone");
    ItemStack init = itemProvider.getItem(itemId)
        .orElseGet(() -> new ItemStack(Material.STONE));
    var builder = itemService.builder(init);
    String name = applyPh(section.getString("display-name", ""), ph);
    if (!name.isBlank()) builder.name(name);
    List<String> lore = section.getStringList("lore");
    if (!lore.isEmpty()) builder.lore(b -> lore.forEach(l -> b.line(applyPh(l, ph))));
    return builder.build();
  }

  private static String applyPh(String text, Map<String, String> ph) {
    if (text == null) return "";
    for (Map.Entry<String, String> e : ph.entrySet()) {
      text = text.replace("<" + e.getKey() + ">", e.getValue());
    }
    return text;
  }

  private static String resolvePlayerName(UUID uuid) {
    Player online = Bukkit.getPlayer(uuid);
    if (online != null) return online.getName();
    String cached = Bukkit.getOfflinePlayer(uuid).getName();
    return cached != null ? cached : uuid.toString();
  }
}

