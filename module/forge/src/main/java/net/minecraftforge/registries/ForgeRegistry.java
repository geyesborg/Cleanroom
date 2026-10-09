/*
 * Minecraft Forge
 * Copyright (c) 2016-2020.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation version 2.1
 * of the License.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */

package net.minecraftforge.registries;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.apache.commons.lang3.Validate;

import com.google.common.base.Preconditions;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.event.RegistryEvent.MissingMappings;
import net.minecraftforge.fml.common.FMLContainer;
import net.minecraftforge.fml.common.FMLLog;
import net.minecraftforge.fml.common.InjectedModContainer;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;

public class ForgeRegistry<V extends IForgeRegistryEntry<V>> implements IForgeRegistryInternal<V>, IForgeRegistryModifiable<V>
{
    public static final boolean DEBUG = Boolean.parseBoolean(System.getProperty("forge.debugRegistryEntries", "false"));
    private final RegistryManager stage;
    private final BiMap<Integer, V> ids = HashBiMap.create();
    private final BiMap<ResourceLocation, V> names = HashBiMap.create();
    private final Class<V> superType;
    private final Map<ResourceLocation, ResourceLocation> aliases = Maps.newHashMap();
    final Map<ResourceLocation, ?> slaves = Maps.newHashMap();
    private final ResourceLocation defaultKey;
    private final CreateCallback<V> create;
    private final AddCallback<V> add;
    private final ClearCallback<V> clear;
    private final ValidateCallback<V> validate;
    private final MissingFactory<V> missing;
    private final Set<Integer> blocked = Sets.newHashSet();
    private final Multimap<ResourceLocation, V> overrides = ArrayListMultimap.create();
    private final BiMap<OverrideOwner, V> owners = HashBiMap.create();
    private final boolean isDelegated;
    private final boolean allowOverrides;
    private final boolean isModifiable;
    private int nextSeed = 0;
    /** Bumped on every structural change; anything that cached an iteration of this registry checks it. */
    private int generation;
    /** Entries whose id the last renumber() moved: name -> {old id, new id}, the FMLModIdMappingEvent shape. */
    private Map<ResourceLocation, Integer[]> lastRenumber = Collections.emptyMap();
    /** name -> vanilla id, captured by captureVanillaBaseline() at vanilla freeze; null for mod-made registries. */
    private Map<ResourceLocation, Integer> vanillaBaseline;

    private V defaultValue = null;
    boolean isFrozen = false;

    // Derived numbering, built by renumber() at freeze time: dense ids 0..N-1, vanilla entries on their own
    // numbers, the rest sorted by name. Null while unfrozen.
    private V[] byId;
    private IdentityHashMap<V, Integer> idOf;

    ForgeRegistry(Class<V> superType, ResourceLocation defaultKey, @Nullable CreateCallback<V> create, @Nullable AddCallback<V> add, @Nullable ClearCallback<V> clear, @Nullable ValidateCallback<V> validate, RegistryManager stage, boolean allowOverrides, boolean isModifiable, @Nullable MissingFactory<V> missing)
    {
        this.stage = stage;
        this.superType = superType;
        this.defaultKey = defaultKey;
        this.create = create;
        this.add = add;
        this.clear = clear;
        this.validate = validate;
        this.missing = missing;
        this.isDelegated = IForgeRegistryEntry.Impl.class.isAssignableFrom(superType); //TODO: Make this IDelegatedRegistryEntry?
        this.allowOverrides = allowOverrides;
        this.isModifiable = isModifiable;
        if (this.create != null)
            this.create.onCreate(this, stage);
    }

    @Override
    public void register(V value)
    {
        add(-1, value);
    }

    @Override
    public Iterator<V> iterator() {
        if (this.byId != null)
        {
            final V[] byId = this.byId;
            return new Iterator<V>()
            {
                int cur = 0;

                @Override
                public boolean hasNext()
                {
                    return cur < byId.length;
                }

                @Override
                public V next()
                {
                    return byId[cur++];
                }
            };
        }
        final List<Integer> order = new ArrayList<Integer>(this.ids.keySet());
        Collections.sort(order);
        return new Iterator<V>()
        {
            int cur = 0;

            @Override
            public boolean hasNext()
            {
                return cur < order.size();
            }

            @Override
            public V next()
            {
                return ids.get(order.get(cur++));
            }
            //TODO add remove support?
        };
    }

    @Override
    public Class<V> getRegistrySuperType()
    {
        return superType;
    }

    @Override
    public void registerAll(@SuppressWarnings("unchecked") V... values)
    {
        for (V value : values)
            register(value);
    }

    @Override
    public boolean containsKey(ResourceLocation key)
    {
        while (key != null)
        {
            if (this.names.containsKey(key))
                return true;
            key = this.aliases.get(key);
        }
        return false;
    }

    @Override
    public boolean containsValue(V value)
    {
        return this.names.containsValue(value);
    }

    @Override
    public V getValue(ResourceLocation key)
    {
        V ret = this.names.get(key);
        key = this.aliases.get(key);
        while (ret == null && key != null)
        {
            ret = this.names.get(key);
            key = this.aliases.get(key);
        }
        return ret == null ? this.defaultValue : ret;
    }

    @Override
    public ResourceLocation getKey(V value)
    {
        ResourceLocation ret = this.names.inverse().get(value);
        return ret == null ? this.defaultKey : ret;
    }

    @Override
    public Set<ResourceLocation> getKeys()
    {
        return Collections.unmodifiableSet(this.names.keySet());
    }

    /**
     * @deprecated use {@link #getValuesCollection} to avoid copying
     */
    @Deprecated
    @Override
    public List<V> getValues()
    {
        return ImmutableList.copyOf(this.names.values());
    }

    @Nonnull
    @Override
    public Collection<V> getValuesCollection()
    {
        return Collections.unmodifiableSet(this.names.values());
    }

    @Override
    public Set<Entry<ResourceLocation, V>> getEntries()
    {
        return Collections.unmodifiableSet(this.names.entrySet());
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T getSlaveMap(ResourceLocation name, Class<T> type)
    {
        return (T)this.slaves.get(name);
    }

    @SuppressWarnings("unchecked")
    @Override
    public void setSlaveMap(ResourceLocation name, Object obj)
    {
        ((Map<ResourceLocation, Object>)this.slaves).put(name, obj);
    }

    public int getID(V value)
    {
        if (this.idOf != null)
        {
            Integer ret = this.idOf.get(value);
            if (ret == null && this.defaultValue != null)
                ret = this.idOf.get(this.defaultValue);
            return ret == null ? -1 : ret.intValue();
        }
        Integer ret = this.ids.inverse().get(value);
        if (ret == null && this.defaultValue != null)
            ret = this.ids.inverse().get(this.defaultValue);
        return ret == null ? -1 : ret.intValue();
    }

    public int getID(ResourceLocation name)
    {
        return getID(this.names.get(name));
    }
    private int getIDRaw(V value)
    {
        if (this.idOf != null)
        {
            Integer ret = this.idOf.get(value);
            return ret == null ? -1 : ret.intValue();
        }
        Integer ret = this.ids.inverse().get(value);
        return ret == null ? -1 : ret.intValue();
    }
    int getIDRaw(ResourceLocation name)
    {
        return getIDRaw(this.names.get(name));
    }

    public V getValue(int id)
    {
        V ret = this.byId != null ? (id >= 0 && id < this.byId.length ? this.byId[id] : null) : this.ids.get(id);
        return ret == null ? this.defaultValue : ret;
    }

    void validateKey()
    {
        if (this.defaultKey != null)
            Validate.notNull(this.defaultValue, "Missing default of ForgeRegistry: " + this.defaultKey + " Type: " + this.superType);
    }

    ForgeRegistry<V> copy(RegistryManager stage)
    {
        return new ForgeRegistry<V>(superType, defaultKey, create, add, clear, validate, stage, allowOverrides, isModifiable, missing);
    }

    int add(int id, V value)
    {
        ModContainer mc = Loader.instance().activeModContainer();
        String owner = mc == null || (mc instanceof InjectedModContainer && ((InjectedModContainer)mc).wrappedContainer instanceof FMLContainer) ? null : mc.getModId().toLowerCase();
        return add(id, value, owner);
    }

    int add(int id, V value, String owner)
    {
        ResourceLocation key = value == null ? null : value.getRegistryName();
        Preconditions.checkNotNull(key, "Can't use a null-name for the registry, object %s.", value);
        Preconditions.checkNotNull(value, "Can't add null-object to the registry, name %s.", key);

        int idToUse = id;
        if (idToUse < 0 || this.ids.containsKey(idToUse) || this.blocked.contains(idToUse))
        {
            while (this.ids.containsKey(this.nextSeed) || this.blocked.contains(this.nextSeed))
            {
                nextSeed++;
            }
            idToUse = nextSeed;
        }

        V oldEntry = getRaw(key);
        if (oldEntry == value) // already registered, return prev registration's id
        {
            FMLLog.bigWarning("Registry {}: The object {} has been registered twice for the same name {}.", this.superType.getSimpleName(), value, key);
            return this.getID(value);
        }
        if (oldEntry != null) // duplicate name
        {
            if (!this.allowOverrides)
                throw new IllegalArgumentException(String.format("The name %s has been registered twice, for %s and %s.", key, getRaw(key), value));
            if (owner == null)
                throw new IllegalStateException(String.format("Could not determine owner for the override on %s. Value: %s", key, value));
            if (DEBUG)
                FMLLog.log.debug("Registry {} Override: {} {} -> {}", this.superType.getSimpleName(), key, oldEntry, value);
            idToUse = this.getID(oldEntry);
        }

        Integer foundId = this.ids.inverse().get(value); //Is this ever possible to trigger with otherThing being different?
        if (foundId != null)
        {
            V otherThing = this.ids.get(foundId);
            throw new IllegalArgumentException(String.format("The object %s{%x} has been registered twice, using the names %s and %s. (Other object at this id is %s{%x})", value, System.identityHashCode(value), getKey(value), key, otherThing, System.identityHashCode(otherThing)));
        }

        if (isLocked())
            throw new IllegalStateException(String.format("The object %s (name %s) is being added too late.", value, key));

        if (defaultKey != null && defaultKey.equals(key))
        {
            if (this.defaultValue != null)
                throw new IllegalStateException(String.format("Attemped to override already set default value. This is not allowed: The object %s (name %s)", value, key));
            this.defaultValue = value;
        }

        this.names.put(key, value);
        this.ids.put(idToUse, value);
        this.generation++;
        this.owners.put(new OverrideOwner(owner == null ? key.getNamespace() : owner, key), value);

        if (isDelegated)
        {
            getDelegate(value).setName(key);
            if (oldEntry != null)
            {
                if (!this.overrides.get(key).contains(oldEntry))
                    this.overrides.put(key, oldEntry);
                this.overrides.get(key).remove(value);
                if (this.stage == RegistryManager.ACTIVE)
                    getDelegate(oldEntry).changeReference(value);
            }
        }

        if (this.add != null)
            this.add.onAdd(this, this.stage, idToUse, value, oldEntry);

        if (DEBUG)
            FMLLog.log.trace("Registry {} add: {} {} {} (req. id {})", this.superType.getSimpleName(), key, idToUse, value, id);

        return idToUse;
    }

    private V getRaw(ResourceLocation key)
    {
        V ret = this.names.get(key);
        key = this.aliases.get(key);
        while (ret == null && key != null)
        {
            ret = this.names.get(key);
            key = this.aliases.get(key);
        }
        return ret;
    }

    @Deprecated //Public for ByteByfUtils only!
    public V getRaw(int id)
    {
        if (this.byId != null)
            return id >= 0 && id < this.byId.length ? this.byId[id] : null;
        return this.ids.get(id);
    }

    void addAlias(ResourceLocation from, ResourceLocation to)
    {
        // Aliases are pure name indirection (MissingMappings REMAP); they never change ids, so the
        // freeze lock does not apply here the way it does to register().
        if (from.equals(to))
        {
            FMLLog.log.warn("Registry {} Ignoring invalid alias: {} -> {}", this.superType.getSimpleName(), from, to);
            return;
        }
        this.aliases.put(from, to);
        if (DEBUG)
            FMLLog.log.trace("Registry {} alias: {} -> {}", this.superType.getSimpleName(), from, to);
    }

    private RegistryDelegate<V> getDelegate(V thing)
    {
        if (isDelegated)
            return (RegistryDelegate<V>)((IForgeRegistryEntry.Impl<V>)thing).delegate;
        else
            throw new IllegalStateException("Tried to get existing delegate from registry that is not delegated.");
    }

    void resetDelegates()
    {
        if (!this.isDelegated)
            return;

        for (V value : this)
            getDelegate(value).changeReference(value);

        for (V value: this.overrides.values())
            getDelegate(value).changeReference(value);
    }

    V getDefault()
    {
        return this.defaultValue;
    }

    void validateContent(ResourceLocation registryName)
    {
        for (V obj : this)
        {
            int id = getID(obj);
            ResourceLocation name = getKey(obj);

            // name lookup failed -> obj is not in the obj<->name map
            if (name == null)
                throw new IllegalStateException(String.format("Registry entry for %s %s, id %d, doesn't yield a name.", registryName, obj, id));

            // id -> obj lookup is inconsistent
            if (getValue(id) != obj)
                throw new IllegalStateException(String.format("Registry entry for id %d, name %s, doesn't yield the expected %s %s.", id, name, registryName, obj));

            // name -> obj lookup is inconsistent
            if (getValue(name) != obj)
                throw new IllegalStateException(String.format("Registry entry for name %s, id %d, doesn't yield the expected %s %s.", name, id, registryName, obj));

            // name -> id lookup is inconsistent
            if (getID(name) != id)
                throw new IllegalStateException(String.format("Registry entry for name %s doesn't yield the expected id %d.", name, id));

            /*
            // entry is blocked, thus should be empty
            if (blockedIds.contains(id))
                throw new IllegalStateException(String.format("Registry entry for %s %s, id %d, name %s, marked as dangling.", registryName, obj, id, name));
             */

            // registry-specific validation
            if (this.validate != null)
                this.validate.onValidate(this, this.stage, id, name, obj);
        }
    }

    @Override
    public void clear()
    {
        if (!this.isModifiable)
            throw new UnsupportedOperationException("Attempted to clear a non-modifiable Forge Registry");

        if (this.isLocked())
            throw new IllegalStateException("Attempted to clear the registry to late.");

        if (this.clear != null)
            this.clear.onClear(this, stage);


        this.aliases.clear();

        this.ids.clear();
        this.names.clear();
        this.nextSeed = 0;
        this.byId = null;
        this.idOf = null;
        this.generation++;
    }

    @Override
    public V remove(ResourceLocation key)
    {
        if (!this.isModifiable)
            throw new UnsupportedOperationException("Attempted to remove from a non-modifiable Forge Registry");

        if (this.isLocked())
            throw new IllegalStateException("Attempted to remove from the registry to late.");

        V value = this.names.remove(key);
        if (value != null)
        {
            Integer id = this.ids.inverse().remove(value);
            if (id == null)
                throw new IllegalStateException("Removed a entry that did not have an associated id: " + key + " " + value.toString() + " This should never happen unless hackery!");

            if (DEBUG)
                FMLLog.log.trace("Registry {} remove: {} {}", this.superType.getSimpleName(), key, id);
            this.generation++;
        }

        return value;
    }

    void block(int id)
    {
        this.blocked.add(id);
    }

    @Override
    public boolean isLocked()
    {
        return this.isFrozen;
    }

    /**
     * Used to control the times where people can modify this registry.
     * Users should only ever register things in the Register<?> events!
     */
    public void freeze()
    {
        this.isFrozen = true;
        renumber();
    }

    void unfreeze()
    {
        this.isFrozen = false;
        this.byId = null;
        this.idOf = null;
        this.generation++;
    }

    int generation()
    {
        return generation;
    }

    Map<ResourceLocation, Integer[]> lastRenumber()
    {
        return lastRenumber;
    }

    /**
     * Records the name -> vanilla id table this registry held at vanilla freeze time. Called by
     * {@link GameData#vanillaSnapshot()}; afterwards {@link #renumber()} pins these names to these
     * numbers wherever they fit.
     */
    void captureVanillaBaseline(Map<ResourceLocation, Integer> baseline)
    {
        this.vanillaBaseline = Collections.unmodifiableMap(new LinkedHashMap<ResourceLocation, Integer>(baseline));
    }

    /**
     * The name -> vanilla id table captured by {@link #captureVanillaBaseline(Map)}, or an empty map
     * if it was never taken.
     */
    public Map<ResourceLocation, Integer> getVanillaBaseline()
    {
        return vanillaBaseline == null ? Collections.<ResourceLocation, Integer>emptyMap() : vanillaBaseline;
    }

    /**
     * Assigns this registry's session numbers, replacing the ids chosen at registration time. Vanilla entries
     * (the names captured by {@link #captureVanillaBaseline(Map)}) keep the number vanilla gave them; every
     * other name fills the remaining ids in {@link ResourceLocation#toString()} order. The result is dense
     * 0..N-1 and is verified before it is installed.
     */
    void renumber()
    {
        IdentityHashMap<V, Integer> idOf = derivedIds();
        Map<ResourceLocation, Integer[]> renumbered = new HashMap<ResourceLocation, Integer[]>();
        for (Entry<ResourceLocation, V> entry : this.names.entrySet())
        {
            int newId = idOf.get(entry.getValue());
            int oldId = getID(entry.getValue()); // still the seed or previous derived numbering
            if (oldId != newId)
            {
                renumbered.put(entry.getKey(), new Integer[] { oldId, newId });
            }
        }
        this.lastRenumber = renumbered;
        int size = this.names.size();
        @SuppressWarnings("unchecked")
        V[] byId = (V[])Array.newInstance(this.superType, size);
        for (Map.Entry<V, Integer> entry : idOf.entrySet())
        {
            byId[entry.getValue()] = entry.getKey();
        }
        this.byId = byId;
        this.idOf = idOf;
        this.generation++; // after the arrays are in place, so a cache rebuilt now sees the new order
        verifyNumbering();
    }

    /**
     * Installs a name order a remote side sent during login: the given names take ids
     * 0..order.size()-1 in the order given and every local name the remote did not send (client-only
     * extras) is appended in {@link ResourceLocation} order. Verified exactly like
     * {@link #renumber()} before it is installed, and freezes the registry: while the
     * remote order is in force this registry belongs to the connection.
     */
    void adoptOrder(List<ResourceLocation> order)
    {
        Set<ResourceLocation> ordered = Sets.newHashSet(order);
        if (ordered.size() != order.size())
            throw new IllegalArgumentException("adoptOrder given an order with a duplicated name");

        int size = this.names.size();
        IdentityHashMap<V, Integer> adopted = new IdentityHashMap<V, Integer>(size * 2);
        for (ResourceLocation name : order)
        {
            V value = this.names.get(name);
            if (value == null)
                throw new IllegalArgumentException(String.format("adoptOrder given %s which this registry does not contain", name));
            adopted.put(value, adopted.size());
        }
        List<ResourceLocation> extras = new ArrayList<ResourceLocation>();
        for (ResourceLocation name : this.names.keySet())
        {
            if (!ordered.contains(name))
                extras.add(name);
        }
        extras.sort(Comparator.comparing(ResourceLocation::toString));
        for (ResourceLocation name : extras)
        {
            adopted.put(this.names.get(name), adopted.size());
        }

        Map<ResourceLocation, Integer[]> renumbered = new HashMap<ResourceLocation, Integer[]>();
        for (Entry<ResourceLocation, V> entry : this.names.entrySet())
        {
            int newId = adopted.get(entry.getValue());
            int oldId = getID(entry.getValue()); // still the previous numbering
            if (oldId != newId)
            {
                renumbered.put(entry.getKey(), new Integer[] { oldId, newId });
            }
        }
        this.lastRenumber = renumbered;

        @SuppressWarnings("unchecked")
        V[] byId = (V[])Array.newInstance(this.superType, size);
        for (Map.Entry<V, Integer> entry : adopted.entrySet())
        {
            byId[entry.getValue()] = entry.getKey();
        }
        this.byId = byId;
        this.idOf = adopted;
        this.generation++; // after the arrays are in place, so a cache rebuilt now sees the new order
        verifyNumbering();
        this.isFrozen = true;
    }

    /**
     * The invariant every installed numbering must satisfy: dense 0..N-1, and the name lookup and the
     * value lookup agree on every id.
     */
    private void verifyNumbering()
    {
        int size = this.names.size();
        ResourceLocation regName = this.stage.getName(this);
        for (int i = 0; i < size; i++)
        {
            if (this.byId[i] == null)
                throw new IllegalStateException(String.format("Registry %s: derived numbering left id %d empty.", regName, i));
        }
        for (Entry<ResourceLocation, V> entry : this.names.entrySet())
        {
            V value = entry.getValue();
            int id = getID(value);
            if (id < 0 || id >= size || getValue(id) != value)
                throw new IllegalStateException(String.format("Registry %s: derived numbering of %s is inconsistent (id %d).", regName, entry.getKey(), id));
            if (getID(entry.getKey()) != id)
                throw new IllegalStateException(String.format("Registry %s: derived numbering of name %s does not match its value's id %d.", regName, entry.getKey(), id));
        }
    }

    /**
     * The value -> id assignment {@link #renumber()} installs, computed from the current
     * contents without changing the registry. The vanilla baseline (names and their vanilla
     * numbers captured by {@link #captureVanillaBaseline(Map)}) pins those entries; every other
     * name fills the remaining ids in {@link ResourceLocation} order.
     */
    private IdentityHashMap<V, Integer> derivedIds()
    {
        int size = this.names.size();
        IdentityHashMap<V, Integer> ret = new IdentityHashMap<V, Integer>(size * 2);
        BitSet used = new BitSet(size);
        ResourceLocation regName = this.stage.getName(this);
        Map<ResourceLocation, Integer> baseline = this.vanillaBaseline;

        List<ResourceLocation> vanillaNames = new ArrayList<ResourceLocation>();
        List<ResourceLocation> moddedNames = new ArrayList<ResourceLocation>();
        for (ResourceLocation name : this.names.keySet())
        {
            if (baseline != null && baseline.containsKey(name))
                vanillaNames.add(name);
            else
                moddedNames.add(name);
        }
        vanillaNames.sort(Comparator.comparingInt(name -> baseline.get(name)));
        moddedNames.sort(Comparator.comparing(ResourceLocation::toString));

        List<V> unclaimed = new ArrayList<V>();
        for (ResourceLocation name : vanillaNames)
        {
            V value = this.names.get(name);
            int id = baseline.get(name);
            if (id >= 0 && id < size && !used.get(id) && !ret.containsKey(value))
            {
                used.set(id);
                ret.put(value, id);
            }
            else if (!ret.containsKey(value))
            {
                unclaimed.add(value);
            }
        }
        for (ResourceLocation name : moddedNames)
        {
            V value = this.names.get(name);
            if (!ret.containsKey(value))
            {
                unclaimed.add(value);
            }
        }
        int next = 0;
        for (V value : unclaimed)
        {
            while (next < size && used.get(next))
            {
                next++;
            }
            if (next >= size)
                throw new IllegalStateException(String.format("Registry %s: derived numbering ran out of ids for %s.", regName, getKey(value)));
            used.set(next);
            ret.put(value, next);
        }
        return ret;
    }

    RegistryEvent.Register<V> getRegisterEvent(ResourceLocation name)
    {
        return new RegistryEvent.Register<V>(name, this);
    }

    void dump(ResourceLocation name)
    {
        List<Integer> ids = Lists.newArrayList();
        getKeys().forEach(n -> ids.add(getID(n)));

        Collections.sort(ids);

        FMLLog.log.trace("Registry Name : {}", name);
        ids.forEach(id -> FMLLog.log.trace("  Registry: {} {} {}", id, getKey(getValue(id)), getValue(id)));
    }


    /**
     * @deprecated ids are derived at freeze; there is nothing to load. Kept so old mods still link.
     */
    @Deprecated
    public void loadIds(Map<ResourceLocation, Integer> ids, Map<ResourceLocation, String> overrides, Map<ResourceLocation, Integer> missing, Map<ResourceLocation, Integer[]> remapped, ForgeRegistry<V> old, ResourceLocation name)
    {
    }

    //Public for tests
    //Public for tests
    public Snapshot makeSnapshot()
    {
        Snapshot ret = new Snapshot();
        this.names.forEach((key, value) -> ret.ids.put(key, getID(value)));
        ret.aliases.putAll(this.aliases);
        ret.blocked.addAll(this.blocked);
        ret.overrides.putAll(getOverrideOwners());
        return ret;
    }

    Map<ResourceLocation, String> getOverrideOwners()
    {
        Map<ResourceLocation, String> ret = Maps.newHashMap();
        for (ResourceLocation key : this.overrides.keySet())
        {
            V obj = this.names.get(key);
            OverrideOwner owner = this.owners.inverse().get(obj);
            if (owner == null && DEBUG)
                FMLLog.log.debug("Registry {} {}: Invalid override {} {}", this.superType.getSimpleName(), this.stage.getName(), key, obj);
            ret.put(key, owner.owner);
        }
        return ret;
    }

    public static class Snapshot
    {
        public final Map<ResourceLocation, Integer> ids = Maps.newHashMap();
        public final Map<ResourceLocation, ResourceLocation> aliases = Maps.newHashMap();
        public final Set<Integer> blocked = Sets.newHashSet();
        public final Set<ResourceLocation> dummied = Sets.newHashSet();
        public final Map<ResourceLocation, String> overrides = Maps.newHashMap();

        public NBTTagCompound write()
        {
            NBTTagCompound data = new NBTTagCompound();

            NBTTagList ids = new NBTTagList();
            this.ids.entrySet().stream().sorted((o1, o2) -> o1.getKey().compareTo(o2.getKey())).forEach(e ->
            {
                NBTTagCompound tag = new NBTTagCompound();
                tag.setString("K", e.getKey().toString());
                tag.setInteger("V", e.getValue());
                ids.appendTag(tag);
            });
            data.setTag("ids", ids);

            NBTTagList aliases = new NBTTagList();
            this.aliases.entrySet().stream().sorted((o1, o2) -> o1.getKey().compareTo(o2.getKey())).forEach(e ->
            {
                NBTTagCompound tag = new NBTTagCompound();
                tag.setString("K", e.getKey().toString());
                tag.setString("V", e.getValue().toString());
                aliases.appendTag(tag);
            });
            data.setTag("aliases", aliases);

            NBTTagList overrides = new NBTTagList();
            this.overrides.entrySet().stream().sorted((o1, o2) -> o1.getKey().compareTo(o2.getKey())).forEach(e ->
            {
                NBTTagCompound tag = new NBTTagCompound();
                tag.setString("K", e.getKey().toString());
                tag.setString("V", e.getValue());
                overrides.appendTag(tag);
            });
            data.setTag("overrides", overrides);

            int[] blocked = this.blocked.stream().mapToInt(x->x).sorted().toArray();
            data.setIntArray("blocked", blocked);

            NBTTagList dummied = new NBTTagList();
            this.dummied.stream().sorted().forEach(e -> dummied.appendTag(new NBTTagString(e.toString())));
            data.setTag("dummied", dummied);

            return data;
        }

        public static Snapshot read(NBTTagCompound nbt)
        {
            Snapshot ret = new Snapshot();
            if (nbt == null)
            {
                return ret;
            }

            NBTTagList list = nbt.getTagList("ids", 10);
            list.forEach(e ->
            {
                NBTTagCompound comp = (NBTTagCompound)e;
                ret.ids.put(new ResourceLocation(comp.getString("K")), comp.getInteger("V"));
            });

            list = nbt.getTagList("aliases", 10);
            list.forEach(e ->
            {
                NBTTagCompound comp = (NBTTagCompound)e;
                String v = comp.getString("V");
                if (v.indexOf(':') == -1) //Forge Bug: https://github.com/MinecraftForge/MinecraftForge/issues/4894 TODO: Remove in 1.13
                {
                    ret.overrides.put(new ResourceLocation(comp.getString("K")), v);
                }
                else
                {
                    ResourceLocation aliask = new ResourceLocation(comp.getString("K"));
                    ResourceLocation aliasv = new ResourceLocation(v);
                    if (aliasv.equals(aliask))
                    {
                        FMLLog.log.warn("Found unrecoverable 4894 bugged alias/override: {} -> {}, skipping.", aliask, aliasv);
                    }
                    else
                    {
                        ret.aliases.put(aliask, aliasv);
                    }
                }
            });

            list = nbt.getTagList("overrides", 10);
            list.forEach(e ->
            {
                NBTTagCompound comp = (NBTTagCompound)e;
                ret.overrides.put(new ResourceLocation(comp.getString("K")), comp.getString("V"));
            });

            int[] blocked = nbt.getIntArray("blocked");
            for (int i : blocked)
            {
                ret.blocked.add(i);
            }

            list = nbt.getTagList("dummied", 10); //10 - NBTTagCompound, Old format. New format is String list. For now we will just merge the old and new. TODO: Remove in 1.13
            list.forEach(e -> ret.dummied.add(new ResourceLocation(((NBTTagCompound)e).getString("K"))));

            list = nbt.getTagList("dummied", 8); //8 - NBTTagString, New format, less redundant/verbose
            list.forEach(e -> ret.dummied.add(new ResourceLocation(((NBTTagString)e).getString())));

            return ret;
        }
    }

    public MissingMappings<?> getMissingEvent(ResourceLocation name, Map<ResourceLocation, Integer> map)
    {
        List<MissingMappings.Mapping<V>> lst = Lists.newArrayList();
        ForgeRegistry<V> pool = RegistryManager.ACTIVE.getRegistry(name);
        map.forEach((rl, id) -> lst.add(new MissingMappings.Mapping<V>(this, pool, rl, id)));
        return new MissingMappings<V>(name, this, lst);
    }

    private static class OverrideOwner
    {
        final String owner;
        final ResourceLocation key;
        private OverrideOwner(String owner, ResourceLocation key)
        {
            this.owner = owner;
            this.key = key;
        }

        public boolean equals(Object o)
        {
            if (this == o)
                return true;

            if (!(o instanceof OverrideOwner))
                return false;

            OverrideOwner oo = (OverrideOwner)o;
            return this.owner.equals(oo.owner) && this.key.equals(oo.key);
        }

        public int hashCode()
        {
            return 31 * this.key.hashCode() + this.owner.hashCode();
        }
    }
}
