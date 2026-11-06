package be.benn0.pkmninstaller.apkm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ApkmParserTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun fixture(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("fixtures/$name")!!.readBytes()

    /** Builds a nested APK (a ZIP containing just AndroidManifest.xml). */
    private fun apk(manifest: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write(manifest)
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    private fun apkm(vararg entries: Pair<String, ByteArray>): File {
        val file = temp.newFile("test.apkm")
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }

    @Test
    fun `parses info json and classifies base and splits`() {
        val file = apkm(
            "info.json" to fixture("info.json"),
            "base.apk" to apk(fixture("base_AndroidManifest.xml")),
            "split_config.arm64_v8a.apk" to apk(fixture("split_config.arm64_v8a_AndroidManifest.xml")),
            "split_bundledtree.apk" to apk(fixture("split_bundledtree_AndroidManifest.xml")),
        )

        val bundle = ApkmParser.parse(file)

        assertEquals("jp.pokemon.pokemontcgp", bundle.info.packageName)
        assertEquals(399771L, bundle.info.versionCode)
        assertEquals(24, bundle.info.minApi)
        assertEquals(listOf("arm64-v8a"), bundle.info.arches)
        assertEquals(5, bundle.info.apkmVersion)

        assertEquals(3, bundle.apks.size)
        val base = bundle.apks.single { it.isBase }
        assertEquals("base", base.splitName)

        val splits = bundle.apks.filterNot { it.isBase }.map { it.splitName }.toSet()
        assertEquals(setOf("config.arm64_v8a", "bundledtree"), splits)
        assertTrue(bundle.totalSize > 0)
    }

    @Test(expected = IllegalStateException::class)
    fun `fails when info json is missing`() {
        val file = apkm("base.apk" to apk(fixture("base_AndroidManifest.xml")))
        ApkmParser.parse(file)
    }

    /**
     * Regression test for the on-device "incorrect header check" failure: the
     * channel-backed source must inflate ZIP entries as raw deflate streams.
     */
    @Test
    fun `parses deflated bundle through a channel source`() {
        val file = apkm(
            "info.json" to fixture("info.json"),
            "base.apk" to apk(fixture("base_AndroidManifest.xml")),
            "split_config.arm64_v8a.apk" to apk(fixture("split_config.arm64_v8a_AndroidManifest.xml")),
            "split_bundledtree.apk" to apk(fixture("split_bundledtree_AndroidManifest.xml")),
        )

        val bundle = java.nio.channels.FileChannel.open(file.toPath()).use { channel ->
            ChannelZipSource(channel).use { ApkmParser.parse(it) }
        }

        assertEquals("jp.pokemon.pokemontcgp", bundle.info.packageName)
        assertEquals(3, bundle.apks.size)
        assertEquals("base", bundle.apks.single { it.isBase }.splitName)
        assertEquals(
            setOf("config.arm64_v8a", "bundledtree"),
            bundle.apks.filterNot { it.isBase }.map { it.splitName }.toSet(),
        )
    }
}
