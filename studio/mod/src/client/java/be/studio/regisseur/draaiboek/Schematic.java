package be.studio.regisseur.draaiboek;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Laadt bouwwerken uit de drie gangbare formaten:
 *  .schem (Sponge v2/v3, WorldEdit), .nbt (vanilla structure block), .litematic (Litematica).
 * Resultaat: compacte parallelle arrays met alleen niet-lucht blokken, genormaliseerd naar (0,0,0).
 */
public final class Schematic {
	public int sx, sy, sz;
	public int unknown; // blokken die deze Minecraft-versie niet kent (overgeslagen)
	public final IntArrayList xs = new IntArrayList(), ys = new IntArrayList(), zs = new IntArrayList();
	public final List<BlockState> states = new ArrayList<>();

	public int size() { return states.size(); }

	public static Schematic load(Path file) throws Exception {
		String n = file.getFileName().toString().toLowerCase(Locale.ROOT);
		CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
		Schematic s = new Schematic();
		if (n.endsWith(".litematic")) s.readLitematic(root);
		else if (n.endsWith(".nbt")) s.readStructure(root);
		else if (n.endsWith(".schem")) s.readSponge(root);
		else throw new IllegalArgumentException("Onbekend formaat (gebruik .schem, .nbt of .litematic): " + n);
		s.normalize();
		if (s.size() == 0) throw new IllegalArgumentException("Bestand bevat geen blokken");
		return s;
	}

	private void add(int x, int y, int z, BlockState st) {
		if (st == null) { unknown++; return; }
		if (st.isAir() || st.getBlock() == Blocks.STRUCTURE_VOID) return;
		xs.add(x); ys.add(y); zs.add(z); states.add(st);
	}

	private static BlockState parse(String key) {
		try {
			return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, key, false).blockState();
		} catch (Exception e) {
			return null;
		}
	}

	private static BlockState fromTag(CompoundTag t) {
		try {
			return NbtUtils.readBlockState(BuiltInRegistries.BLOCK, t);
		} catch (Exception e) {
			return null;
		}
	}

	// ---------------- Sponge .schem ----------------
	private void readSponge(CompoundTag root) {
		if (root.getCompound("Schematic").isPresent()) root = root.getCompoundOrEmpty("Schematic"); // v3
		int w = root.getShort("Width").orElse((short) 0) & 0xFFFF;
		int h = root.getShort("Height").orElse((short) 0) & 0xFFFF;
		int l = root.getShort("Length").orElse((short) 0) & 0xFFFF;
		CompoundTag pal;
		byte[] data;
		if (root.getCompound("Blocks").isPresent()) { // v3
			CompoundTag b = root.getCompoundOrEmpty("Blocks");
			pal = b.getCompoundOrEmpty("Palette");
			data = b.getByteArray("Data").orElse(new byte[0]);
		} else { // v2
			pal = root.getCompoundOrEmpty("Palette");
			data = root.getByteArray("BlockData").orElse(new byte[0]);
		}
		int max = 0;
		for (String k : pal.keySet()) max = Math.max(max, pal.getIntOr(k, 0));
		BlockState[] table = new BlockState[max + 1];
		boolean[] known = new boolean[max + 1];
		for (String k : pal.keySet()) {
			int id = pal.getIntOr(k, 0);
			table[id] = parse(k);
			known[id] = true;
		}
		int i = 0, idx = 0, layer = w * l;
		while (i < data.length && layer > 0) {
			int v = 0, shift = 0;
			byte b;
			do { // varint
				b = data[i++];
				v |= (b & 0x7F) << shift;
				shift += 7;
			} while ((b & 0x80) != 0 && i < data.length);
			if (v >= 0 && v < table.length && known[v]) add(idx % w, idx / layer, (idx / w) % l, table[v]);
			idx++;
		}
	}

	// ---------------- vanilla structure .nbt ----------------
	private void readStructure(CompoundTag root) {
		ListTag pal = root.getListOrEmpty("palette");
		if (pal.size() == 0) pal = root.getListOrEmpty("palettes").getListOrEmpty(0);
		BlockState[] table = new BlockState[pal.size()];
		for (int i = 0; i < table.length; i++) table[i] = fromTag(pal.getCompoundOrEmpty(i));
		ListTag blocks = root.getListOrEmpty("blocks");
		for (int i = 0; i < blocks.size(); i++) {
			CompoundTag b = blocks.getCompoundOrEmpty(i);
			ListTag p = b.getListOrEmpty("pos");
			int st = b.getIntOr("state", -1);
			if (st >= 0 && st < table.length) add(p.getIntOr(0, 0), p.getIntOr(1, 0), p.getIntOr(2, 0), table[st]);
		}
	}

	// ---------------- Litematica .litematic ----------------
	private void readLitematic(CompoundTag root) {
		CompoundTag regions = root.getCompoundOrEmpty("Regions");
		for (String name : regions.keySet()) {
			CompoundTag r = regions.getCompoundOrEmpty(name);
			CompoundTag pos = r.getCompoundOrEmpty("Position"), size = r.getCompoundOrEmpty("Size");
			int rx = size.getIntOr("x", 0), ry = size.getIntOr("y", 0), rz = size.getIntOr("z", 0);
			int ax = Math.abs(rx), ay = Math.abs(ry), az = Math.abs(rz);
			// negatieve maten: de hoek 'Position' is dan de maximale hoek
			int ox = pos.getIntOr("x", 0) + (rx < 0 ? rx + 1 : 0);
			int oy = pos.getIntOr("y", 0) + (ry < 0 ? ry + 1 : 0);
			int oz = pos.getIntOr("z", 0) + (rz < 0 ? rz + 1 : 0);
			ListTag pal = r.getListOrEmpty("BlockStatePalette");
			BlockState[] table = new BlockState[pal.size()];
			for (int i = 0; i < table.length; i++) table[i] = fromTag(pal.getCompoundOrEmpty(i));
			long[] arr = r.getLongArray("BlockStates").orElse(new long[0]);
			int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(1, table.length - 1)));
			long mask = (1L << bits) - 1L;
			long total = (long) ax * ay * az;
			for (long idx = 0; idx < total; idx++) {
				long start = idx * bits;
				int a = (int) (start >> 6), e = (int) (((idx + 1) * bits - 1) >> 6), sb = (int) (start & 63);
				if (e >= arr.length) break;
				long v = a == e ? (arr[a] >>> sb) & mask : ((arr[a] >>> sb) | (arr[e] << (64 - sb))) & mask;
				if (v < table.length) {
					int x = (int) (idx % ax), z = (int) ((idx / ax) % az), y = (int) (idx / ((long) ax * az));
					add(ox + x, oy + y, oz + z, table[(int) v]);
				}
			}
		}
	}

	private void normalize() {
		if (size() == 0) return;
		int mnx = Integer.MAX_VALUE, mny = mnx, mnz = mnx, mxx = Integer.MIN_VALUE, mxy = mxx, mxz = mxx;
		for (int i = 0; i < size(); i++) {
			mnx = Math.min(mnx, xs.getInt(i)); mxx = Math.max(mxx, xs.getInt(i));
			mny = Math.min(mny, ys.getInt(i)); mxy = Math.max(mxy, ys.getInt(i));
			mnz = Math.min(mnz, zs.getInt(i)); mxz = Math.max(mxz, zs.getInt(i));
		}
		for (int i = 0; i < size(); i++) {
			xs.set(i, xs.getInt(i) - mnx); ys.set(i, ys.getInt(i) - mny); zs.set(i, zs.getInt(i) - mnz);
		}
		sx = mxx - mnx + 1; sy = mxy - mny + 1; sz = mxz - mnz + 1;
	}
}
