/**
 * Fast set blocks
 * Because default world.setBlock or block.setType is very slow (lighting + packets)
 * 
 * See: https://www.spigotmc.org/threads/how-to-set-blocks-incredibly-fast.476097/
 * 
 * ```
 * This is code I actually use.
 * The code itself is very clear.
 * What I do here is just simple 3 steps:
 * 1. modify block data in memory
 * 2. update lighting
 * 3. unload & load chunk data
 * 
 * Minecraft server contains data as of what chunks are loaded for players. However, it's not visible and we have to check whether a modified chunk is in view distance or not.
 * 
 * There are several cons that you have to take care of.
 * 1. You can modify blocks in unloaded chunks. However, it's slower than usual.
 * 2. Block update does not happen.
 * 3. Light update is not perfect when you edit unloaded chunks
 * 4. Light update doesn't work well when you have Sodium or some other lighting mod installed, because lighting is cached and ignore lighting update packet
 * ```
 * - Toshimichi
 *
 * 1.21.1 - 10-16-2024 - tlm920
 */
package phonon.blockedit

import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.lighting.LevelLightEngine
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.craftbukkit.CraftWorld
import org.bukkit.craftbukkit.block.CraftBlockState
import org.bukkit.craftbukkit.entity.CraftPlayer
import java.util.*

public class FastBlockEditSession(
    val bukkitWorld: org.bukkit.World
) {
    private val world: World = bukkitWorld
    private val level: ServerLevel = (bukkitWorld as CraftWorld).handle
    private val modified: HashMap<BlockPos, BlockState> = hashMapOf()

    public fun setBlock(x: Int, y: Int, z: Int, material: Material) {
        modified[BlockPos(x, y, z)] = (material.createBlockData().createBlockState() as CraftBlockState).handle;
    }

    public fun getBlock(x: Int, y: Int, z: Int): Material {
        val bState = modified[BlockPos(x, y, z)]
        if ( bState != null ) {
            return bState.bukkitMaterial
        }
        return Location(bukkitWorld, x.toDouble(), y.toDouble(), z.toDouble()).block.type
    }

    public fun update(updateLighting: Boolean) {

        //modify blocks
        val chunks: HashSet<LevelChunk> = hashSetOf()
        for ( (bPos, bState) in modified ) {
            val chunk = level.getChunkAt(bPos)
            chunks.add(chunk)
            chunk.setBlockState(bPos, bState, false)
        }

        // update lights
        val lightingEngine: LevelLightEngine = level.lightEngine
        if ( updateLighting ) {
            for ( pos in modified.keys ) {
                lightingEngine.checkBlock(pos)
            }
        }


        // sync chunk data
        for ( chunk in chunks ) {
            val packet = ClientboundLevelChunkWithLightPacket(chunk, lightingEngine, null, null, false)

            for ( p in Bukkit.getOnlinePlayers() ) {
                val sp: ServerPlayer = (p as CraftPlayer).handle
                val dist = Bukkit.getViewDistance() + 1
                val chunkX = p.chunk.x
                val chunkZ = p.chunk.z
                if ( chunk.getPos().x < chunkX - dist ||
                     chunk.getPos().x > chunkX + dist ||
                     chunk.getPos().z < chunkZ - dist ||
                     chunk.getPos().z > chunkZ + dist ) {
                    continue
                }
                sp.connection.sendPacket(packet)
            }
        }

        //clear modified blocks
        modified.clear()
    }
}