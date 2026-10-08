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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import net.minecraft.util.ResourceLocation;

/**
 * Login-time registry verification. Registry numbers are derived identically on both sides of a
 * connection (see {@link ForgeRegistry#renumber}), so instead of the server's name-to-id map the
 * client only needs proof that its name list matches: a count plus a hash of the ordered names.
 */
public final class RegistrySync
{
    public record Entry(ResourceLocation registry, int count, long hash) { }

    private RegistrySync() { }

    /**
     * SHA-256 over, for id = 0..count-1, the UTF-8 bytes of the entry's registry name followed by a
     * single 0x00 byte; returns the first 8 bytes of the digest as a big-endian long.
     */
    public static long hash(ForgeRegistry<?> reg, int count)
    {
        return hashTyped(reg, count);
    }

    @SuppressWarnings("deprecation") // getRaw(int) is public for id-indexed reads like this
    private static <V extends IForgeRegistryEntry<V>> long hashTyped(ForgeRegistry<V> reg, int count)
    {
        MessageDigest sha;
        try
        {
            sha = MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
        for (int i = 0; i < count; i++)
        {
            V value = reg.getRaw(i);
            if (value == null)
                throw new IllegalStateException("Registry has no entry at id " + i + " of " + count + " (is it frozen?)");
            ResourceLocation name = reg.getKey(value);
            if (name == null)
                throw new IllegalStateException("Registry entry at id " + i + " has no name");
            sha.update(name.toString().getBytes(StandardCharsets.UTF_8));
            sha.update((byte)0);
        }
        byte[] digest = sha.digest();
        long ret = 0;
        for (int i = 0; i < 8; i++)
        {
            ret = (ret << 8) | (digest[i] & 0xFFL);
        }
        return ret;
    }

    /**
     * One Entry per registry that {@link RegistryManager#takeSnapshot(boolean)} would include, in
     * registry-name order for a deterministic wire form.
     */
    public static List<Entry> local(RegistryManager mgr, boolean savingToDisc)
    {
        Set<ResourceLocation> keys = savingToDisc ? mgr.persisted : mgr.registries.keySet();
        List<ResourceLocation> names = new ArrayList<>(keys);
        names.sort(Comparator.comparing(ResourceLocation::toString));
        List<Entry> ret = new ArrayList<>(names.size());
        for (ResourceLocation name : names)
        {
            ForgeRegistry<?> reg = mgr.registries.get(name);
            int count = reg.getKeys().size();
            ret.add(new Entry(name, count, hash(reg, count)));
        }
        return ret;
    }

    /**
     * Verifies this side's frozen registries against the server's entries. Returns null when every
     * remote entry matches; otherwise one line per failing registry. Registries the client has but
     * the server does not are client-only and ignored.
     */
    @Nullable
    public static String check(List<Entry> remote, RegistryManager local)
    {
        List<String> problems = new ArrayList<>();
        for (Entry entry : remote)
        {
            ForgeRegistry<?> reg = local.registries.get(entry.registry());
            if (reg == null)
            {
                problems.add(String.format("Registry %s: present on the server (%d entries), absent on this client.", entry.registry(), entry.count()));
                continue;
            }
            int localCount = reg.getKeys().size();
            if (localCount < entry.count())
            {
                problems.add(String.format("Registry %s: the server has %d entries, this client has %d.", entry.registry(), entry.count(), localCount));
                continue;
            }
            if (hash(reg, entry.count()) != entry.hash())
            {
                problems.add(String.format("Registry %s: the first %d entries differ between server and client (both have %d/%d).", entry.registry(), entry.count(), entry.count(), localCount));
            }
        }
        return problems.isEmpty() ? null : String.join("\n", problems);
    }
}
