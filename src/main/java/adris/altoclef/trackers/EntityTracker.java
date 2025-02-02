package adris.altoclef.trackers;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.mixins.PersistentProjectileEntityAccessor;
import adris.altoclef.trackers.blacklisting.EntityLocateBlacklist;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.baritone.BaritoneHelper;
import adris.altoclef.util.baritone.CachedProjectile;
import adris.altoclef.util.helpers.ProjectileHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.*;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.function.Predicate;

@SuppressWarnings("rawtypes")
/*
 * Keeps track of entities so we can search/grab them.
 */
public class EntityTracker extends Tracker {

    private final HashMap<Item, List<ItemEntity>> _itemDropLocations = new HashMap<>();

    private final HashMap<Class, List<Entity>> _entityMap = new HashMap<>();

    private final List<Entity> _closeEntities = new ArrayList<>();
    private final List<Entity> _hostiles = new ArrayList<>();

    private final List<CachedProjectile> _projectiles = new ArrayList<>();

    private final HashMap<String, PlayerEntity> _playerMap = new HashMap<>();
    private final HashMap<String, Vec3d> _playerLastCoordinates = new HashMap<>();

    private final EntityLocateBlacklist _entityBlacklist = new EntityLocateBlacklist();

    public EntityTracker(TrackerManager manager) {
        super(manager);
    }

    public static boolean isHostileToPlayer(AltoClef mod, Entity mob) {
        boolean angry = isAngryAtPlayer(mob);
        if (mob instanceof LivingEntity entity) {
            return angry && entity.canSee(mod.getPlayer());
        }
        return angry;
    }

    /**
     * Squash a class that may have sub classes into one distinguishable class type.
     * For ease of use.
     *
     * @param type: An entity class that may have a 'simpler' class to squash to
     * @return what the given entity class should be read as/catalogued as.
     */
    private static Class squashType(Class type) {
        // Squash types for ease of use
        if (PlayerEntity.class.isAssignableFrom(type)) {
            return PlayerEntity.class;
        }
        return type;
    }

    public static boolean isAngryAtPlayer(Entity hostile) {
        // TODO: Ignore on Peaceful difficulty.
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        // NOTE: These do not work.
        if (hostile instanceof EndermanEntity enderman) {
            return enderman.isAngryAt(player) && enderman.isAngry();
        }
        // Hoglins by default are pretty mad.
        if (hostile instanceof HoglinEntity) {
            return true;
        }
        if (hostile instanceof ZombifiedPiglinEntity zombie) {
            // Will ALWAYS be false.
            return zombie.hasAngerTime() && zombie.isAngryAt(player);
        }
        return !isTradingPiglin(hostile);
    }

    public static boolean isTradingPiglin(Entity entity) {
        if (entity instanceof PiglinEntity pig) {
            for (ItemStack stack : pig.getItemsHand()) {
                if (stack.getItem().equals(Items.GOLD_INGOT)) {
                    // We're trading with this one, ignore it.
                    return true;
                }
            }
        }
        return false;
    }

    public ItemEntity getClosestItemDrop(Item... items) {
        return getClosestItemDrop(_mod.getPlayer().getPos(), items);
    }
    public ItemEntity getClosestItemDrop(Vec3d position, Item... items) {
        return getClosestItemDrop(position, entity -> true, items);
    }
    public ItemEntity getClosestItemDrop(Vec3d position, ItemTarget... items) {
        return getClosestItemDrop(position, entity -> true, items);
    }
    public ItemEntity getClosestItemDrop(Predicate<ItemEntity> acceptPredicate, Item... items) {
        return getClosestItemDrop(_mod.getPlayer().getPos(), acceptPredicate, items);
    }
    public ItemEntity getClosestItemDrop(Vec3d position, Predicate<ItemEntity> acceptPredicate, Item... items) {
        ensureUpdated();
        ItemTarget[] tempTargetList = new ItemTarget[items.length];
        for (int i = 0; i < items.length; ++i) {
            tempTargetList[i] = new ItemTarget(items[i], 9999999);
        }
        return getClosestItemDrop(position, acceptPredicate, tempTargetList);
    }

    public ItemEntity getClosestItemDrop(Vec3d position, Predicate<ItemEntity> acceptPredicate, ItemTarget... targets) {
        ensureUpdated();
        if (targets.length == 0) {
            Debug.logError("You asked for the drop position of zero items... Most likely a typo.");
            return null;
        }
        if (!itemDropped(targets)) {
            Debug.logError("You forgot to check for whether item (example): " + targets[0].getMatches()[0].getTranslationKey() + " was dropped before finding its drop location.");
            return null;
        }

        ItemEntity closestEntity = null;
        float minCost = Float.POSITIVE_INFINITY;
        for (ItemTarget target : targets) {
            for (Item item : target.getMatches()) {
                if (!itemDropped(item)) continue;
                for (ItemEntity entity : _itemDropLocations.get(item)) {
                    if (_entityBlacklist.unreachable(entity)) continue;
                    if (!entity.getStack().getItem().equals(item)) continue;
                    if (!acceptPredicate.test(entity)) continue;

                    float cost = (float) BaritoneHelper.calculateGenericHeuristic(position, entity.getPos());
                    if (cost < minCost) {
                        minCost = cost;
                        closestEntity = entity;
                    }
                }
            }
        }
        return closestEntity;
    }

    public Entity getClosestEntity(Class... entityTypes) {
        return getClosestEntity(_mod.getPlayer().getPos(), entityTypes);
    }
    public Entity getClosestEntity(Vec3d position, Class... entityTypes) {
        return this.getClosestEntity(position, (entity) -> true, entityTypes);
    }
    public Entity getClosestEntity(Predicate<Entity> acceptPredicate, Class... entityTypes) {
        return getClosestEntity(_mod.getPlayer().getPos(), acceptPredicate, entityTypes);
    }
    public Entity getClosestEntity(Vec3d position, Predicate<Entity> acceptPredicate, Class... entityTypes) {
        Entity closestEntity = null;
        double minCost = Float.POSITIVE_INFINITY;
        for (Class toFind : entityTypes) {
            if (_entityMap.containsKey(toFind)) {
                for (Entity entity : _entityMap.get(toFind)) {
                    // Don't accept entities that no longer exist
                    if (!entity.isAlive()) continue;
                    if (!acceptPredicate.test(entity)) continue;
                    double cost = entity.squaredDistanceTo(position);
                    if (cost < minCost) {
                        minCost = cost;
                        closestEntity = entity;
                    }
                }
            }
        }
        return closestEntity;
    }

    public boolean itemDropped(Item... items) {
        ensureUpdated();
        for (Item item : items) {
            if (_itemDropLocations.containsKey(item)) {
                // Find a non-blacklisted item
                for (ItemEntity entity : _itemDropLocations.get(item)) {
                    if (!_entityBlacklist.unreachable(entity)) return true;
                }
            }
        }
        return false;
    }

    public boolean itemDropped(ItemTarget... targets) {
        ensureUpdated();
        for (ItemTarget target : targets) {
            if (itemDropped(target.getMatches())) return true;
        }
        return false;
    }

    public boolean entityFound(Class... types) {
        ensureUpdated();
        for (Class type : types) {
            if (_entityMap.containsKey(type)) return true;
        }
        return false;
    }

    public <T extends Entity> List<T> getTrackedEntities(Class<T> type) {
        ensureUpdated();
        if (!entityFound(type)) {
            return Collections.emptyList();
        }
        synchronized (BaritoneHelper.MINECRAFT_LOCK) {
            //noinspection unchecked
            return (List<T>) _entityMap.get(type);
        }
    }

    public List<Entity> getCloseEntities() {
        ensureUpdated();
        synchronized (BaritoneHelper.MINECRAFT_LOCK) {
            return _closeEntities;
        }
    }

    public List<CachedProjectile> getProjectiles() {
        ensureUpdated();
        synchronized (BaritoneHelper.MINECRAFT_LOCK) {
            return _projectiles;
        }
    }

    public List<Entity> getHostiles() {
        ensureUpdated();
        synchronized (BaritoneHelper.MINECRAFT_LOCK) {
            return _hostiles;
        }
    }

    public boolean isPlayerLoaded(String name) {
        ensureUpdated();
        synchronized (BaritoneHelper.MINECRAFT_LOCK) {
            return _playerMap.containsKey(name);
        }
    }

    public Vec3d getPlayerMostRecentPosition(String name) {
        ensureUpdated();
        synchronized (BaritoneHelper.MINECRAFT_LOCK) {
            if (_playerLastCoordinates.containsKey(name)) {
                return _playerLastCoordinates.get(name);
            }
        }
        return null;
    }

    public PlayerEntity getPlayerEntity(String name) {
        if (isPlayerLoaded(name)) {
            synchronized (BaritoneHelper.MINECRAFT_LOCK) {
                return _playerMap.get(name);
            }
        }
        return null;
    }

    public void requestEntityUnreachable(Entity entity) {
        _entityBlacklist.blackListItem(_mod, entity, 2);
    }

    public boolean isEntityReachable(Entity entity) {
        return !_entityBlacklist.unreachable(entity);
    }

    @Override
    protected synchronized void updateState() {
        synchronized (BaritoneHelper.MINECRAFT_LOCK) {
            _itemDropLocations.clear();
            _entityMap.clear();
            _closeEntities.clear();
            _projectiles.clear();
            _hostiles.clear();
            _playerMap.clear();
            if (MinecraftClient.getInstance().world == null) return;

            // Loop through all entities and track 'em
            for (Entity entity : MinecraftClient.getInstance().world.getEntities()) {

                // Catalogue based on type. Some types may get "squashed" or combined into one.
                Class type = entity.getClass();
                type = squashType(type);

                //noinspection ConstantConditions
                if (entity == null || !entity.isAlive()) continue;

                // Don't catalogue our own player.
                if (type == PlayerEntity.class && entity.equals(_mod.getPlayer())) continue;

                if (!_entityMap.containsKey(type)) {
                    _entityMap.put(type, new ArrayList<>());
                }
                _entityMap.get(type).add(entity);

                if (_mod.getControllerExtras().inRange(entity)) {
                    _closeEntities.add(entity);
                }

                if (entity instanceof ItemEntity ientity) {
                    Item droppedItem = ientity.getStack().getItem();

                    if (!_itemDropLocations.containsKey(droppedItem)) {
                        _itemDropLocations.put(droppedItem, new ArrayList<>());
                    }
                    _itemDropLocations.get(droppedItem).add(ientity);
                } else if (entity instanceof MobEntity) {

                    //noinspection ConstantConditions
                    if (entity instanceof HostileEntity || entity instanceof HoglinEntity || entity instanceof ZoglinEntity) {

                        if (isHostileToPlayer(_mod, entity)) {

                            // Check if the mob is facing us or is close enough
                            boolean closeEnough = entity.isInRange(_mod.getPlayer(), 26);

                            //Debug.logInternal("TARGET: " + hostile.is);
                            if (closeEnough) {
                                _hostiles.add(entity);
                            }
                        }
                    }
                } else if (entity instanceof ProjectileEntity projEntity) {
                    if (!_mod.getBehaviour().shouldAvoidDodgingProjectile(entity)) {
                        CachedProjectile proj = new CachedProjectile();

                        boolean inGround = false;
                        // Get projectile "inGround" variable
                        if (entity instanceof PersistentProjectileEntity) {
                            inGround = ((PersistentProjectileEntityAccessor) entity).isInGround();
                        }

                        if (!inGround) {
                            proj.position = projEntity.getPos();
                            proj.velocity = projEntity.getVelocity();
                            proj.gravity = ProjectileHelper.hasGravity(projEntity) ? ProjectileHelper.GRAVITY_ACCEL : 0;
                            proj.projectileType = projEntity.getClass();
                            _projectiles.add(proj);
                        }
                    }
                } else if (entity instanceof PlayerEntity player) {
                    String name = player.getName().getString();
                    _playerMap.put(name, player);
                    _playerLastCoordinates.put(name, player.getPos());
                }
            }
        }
    }

    @Override
    protected void reset() {
        // Dirty clears everything else.
        _entityBlacklist.clear();
    }
}
