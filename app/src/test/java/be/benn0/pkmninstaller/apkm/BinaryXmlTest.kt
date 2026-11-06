package be.benn0.pkmninstaller.apkm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BinaryXmlTest {

    private fun fixture(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("fixtures/$name")!!.readBytes()

    @Test
    fun `base apk manifest has no split`() {
        val split = BinaryXml.readSplitName(fixture("base_AndroidManifest.xml"))
        assertNull(split)
    }

    @Test
    fun `config split manifest reports its split name`() {
        val split = BinaryXml.readSplitName(fixture("split_config.arm64_v8a_AndroidManifest.xml"))
        assertEquals("config.arm64_v8a", split)
    }

    @Test
    fun `bundledtree split manifest reports its split name`() {
        val split = BinaryXml.readSplitName(fixture("split_bundledtree_AndroidManifest.xml"))
        assertEquals("bundledtree", split)
    }
}
