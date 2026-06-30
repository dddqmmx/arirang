package asia.nana7mi.arirang.util

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

class ZipUtilsTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    @Test
    fun zipFiles_singleFile_createsValidZip() {
        val src = tempDir.newFolder("src")
        val file = File(src, "test.txt")
        file.writeText("hello world")

        val zipFile = tempDir.newFile("out.zip")
        FileOutputStream(zipFile).use { ZipUtils.zipFiles(listOf(src), it) }

        val entries = readZipEntries(zipFile)
        assertEquals(1, entries.size)
        assertEquals("src/test.txt", entries[0].name)
        assertEquals("hello world", entries[0].content)
    }

    @Test
    fun zipFiles_multipleFiles_createsValidZipWithAllEntries() {
        val src = tempDir.newFolder("src")
        File(src, "a.txt").writeText("aaa")
        File(src, "b.txt").writeText("bbb")

        val zipFile = tempDir.newFile("out.zip")
        FileOutputStream(zipFile).use { ZipUtils.zipFiles(listOf(src), it) }

        val entries = readZipEntries(zipFile).sortedBy { it.name }
        assertEquals(2, entries.size)
        assertEquals("src/a.txt", entries[0].name)
        assertEquals("aaa", entries[0].content)
        assertEquals("src/b.txt", entries[1].name)
        assertEquals("bbb", entries[1].content)
    }

    @Test
    fun zipFiles_nestedDirectories_preservesStructure() {
        val src = tempDir.newFolder("src")
        val subDir = File(src, "sub")
        subDir.mkdirs()
        File(subDir, "deep.txt").writeText("deep content")
        File(src, "root.txt").writeText("root content")

        val zipFile = tempDir.newFile("out.zip")
        FileOutputStream(zipFile).use { ZipUtils.zipFiles(listOf(src), it) }

        val entries = readZipEntries(zipFile).sortedBy { it.name }
        assertEquals(2, entries.size)
        assertEquals("src/root.txt", entries[0].name)
        assertEquals("root content", entries[0].content)
        assertEquals("src/sub/deep.txt", entries[1].name)
        assertEquals("deep content", entries[1].content)
    }

    @Test
    fun zipFiles_multipleDirectories_usesDirNameAsRoot() {
        val dir1 = tempDir.newFolder("dir1")
        File(dir1, "one.txt").writeText("one")
        val dir2 = tempDir.newFolder("dir2")
        File(dir2, "two.txt").writeText("two")

        val zipFile = tempDir.newFile("out.zip")
        FileOutputStream(zipFile).use { ZipUtils.zipFiles(listOf(dir1, dir2), it) }

        val entries = readZipEntries(zipFile).sortedBy { it.name }
        assertEquals(2, entries.size)
        assertEquals("dir1/one.txt", entries[0].name)
        assertEquals("dir2/two.txt", entries[1].name)
    }

    @Test
    fun zipFiles_emptyDirectory_skipped() {
        val src = tempDir.newFolder("src")
        val emptyDir = File(src, "empty")
        emptyDir.mkdirs()
        File(src, "real.txt").writeText("real")

        val zipFile = tempDir.newFile("out.zip")
        FileOutputStream(zipFile).use { ZipUtils.zipFiles(listOf(src), it) }

        val entries = readZipEntries(zipFile)
        assertEquals(1, entries.size)
        assertEquals("real", entries[0].content)
    }

    @Test
    fun zipFiles_missingDirectory_skipped() {
        val src = tempDir.newFolder("src")
        File(src, "hello.txt").writeText("hi")

        val missingDir = File(tempDir.root, "nonexistent")
        val zipFile = tempDir.newFile("out.zip")
        FileOutputStream(zipFile).use { ZipUtils.zipFiles(listOf(src, missingDir), it) }

        val entries = readZipEntries(zipFile)
        assertEquals(1, entries.size)
        assertEquals("src/hello.txt", entries[0].name)
    }

    @Test
    fun unzipFiles_simpleZip_restoresAllFiles() {
        val src = tempDir.newFolder("src")
        File(src, "a.txt").writeText("alpha")
        File(src, "b.txt").writeText("beta")

        val zipFile = tempDir.newFile("out.zip")
        FileOutputStream(zipFile).use { ZipUtils.zipFiles(listOf(src), it) }

        val dest = tempDir.newFolder("dest")
        FileInputStream(zipFile).use { ZipUtils.unzipFiles(it, dest) }

        val restoredSrc = File(dest, "src")
        assertTrue(restoredSrc.isDirectory)
        assertEquals("alpha", File(restoredSrc, "a.txt").readText())
        assertEquals("beta", File(restoredSrc, "b.txt").readText())
    }

    @Test
    fun unzipFiles_nestedDirectories_restoresStructure() {
        val src = tempDir.newFolder("src")
        val sub = File(src, "sub")
        sub.mkdirs()
        File(sub, "nested.txt").writeText("nested")
        File(src, "root.txt").writeText("root")

        val zipFile = tempDir.newFile("out.zip")
        FileOutputStream(zipFile).use { ZipUtils.zipFiles(listOf(src), it) }

        val dest = tempDir.newFolder("dest")
        FileInputStream(zipFile).use { ZipUtils.unzipFiles(it, dest) }

        val restoredRoot = File(dest, "src")
        assertEquals("root", File(restoredRoot, "root.txt").readText())
        assertEquals("nested", File(File(restoredRoot, "sub"), "nested.txt").readText())
    }

    @Test
    fun unzipFiles_binaryContent_restoresExactBytes() {
        val src = tempDir.newFolder("src")
        val binaryBytes = ByteArray(4096) { (it % 256).toByte() }
        File(src, "binary.bin").writeBytes(binaryBytes)

        val zipFile = tempDir.newFile("out.zip")
        FileOutputStream(zipFile).use { ZipUtils.zipFiles(listOf(src), it) }

        val dest = tempDir.newFolder("dest")
        FileInputStream(zipFile).use { ZipUtils.unzipFiles(it, dest) }

        val restored = File(File(dest, "src"), "binary.bin")
        assertArrayEquals(binaryBytes, restored.readBytes())
    }

    @Test
    fun unzipFiles_emptyZip_createsNoFiles() {
        val src = tempDir.newFolder("src")
        val zipFile = tempDir.newFile("empty.zip")
        FileOutputStream(zipFile).use { ZipUtils.zipFiles(listOf(src), it) }

        val dest = tempDir.newFolder("dest")
        FileInputStream(zipFile).use { ZipUtils.unzipFiles(it, dest) }

        // empty directory not added, so dest should have no files
        assertTrue(dest.listFiles()?.isEmpty() ?: true)
    }

    @Test
    fun zipThenUnzip_roundtrip_preservesAllContent() {
        val src = tempDir.newFolder("data")
        File(src, "config.json").writeText("""{"key":"value"}""")
        val sub = File(src, "sub")
        sub.mkdirs()
        File(sub, "settings.xml").writeText("<xml><enabled>true</enabled></xml>")
        File(sub, "numbers.txt").writeText("1\n2\n3\n")

        val zipFile = tempDir.newFile("roundtrip.zip")
        FileOutputStream(zipFile).use { ZipUtils.zipFiles(listOf(src), it) }

        val dest = tempDir.newFolder("restored")
        FileInputStream(zipFile).use { ZipUtils.unzipFiles(it, dest) }

        val restoredData = File(dest, "data")
        assertEquals("""{"key":"value"}""", File(restoredData, "config.json").readText())
        assertEquals("<xml><enabled>true</enabled></xml>", File(File(restoredData, "sub"), "settings.xml").readText())
        assertEquals("1\n2\n3\n", File(File(restoredData, "sub"), "numbers.txt").readText())
    }

    @Test
    fun unzipFiles_zipSlip_throwsSecurityException() {
        val dest = tempDir.newFolder("dest")
        val destCanonical = dest.canonicalPath

        val zipFile = tempDir.newFile("evil.zip")
        java.util.zip.ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            val entry = java.util.zip.ZipEntry("../../../etc/hosts")
            zos.putNextEntry(entry)
            zos.write("evil".toByteArray())
            zos.closeEntry()
        }

        try {
            FileInputStream(zipFile).use { ZipUtils.unzipFiles(it, dest) }
            fail("Expected SecurityException for Zip Slip")
        } catch (e: SecurityException) {
            assertTrue(e.message?.contains("outside") == true)
        }
    }

    @Test
    fun zipFiles_largeFile_handlesCorrectly() {
        val src = tempDir.newFolder("src")
        val largeContent = CharArray(50_000) { 'A' }.concatToString()
        File(src, "large.txt").writeText(largeContent)

        val zipFile = tempDir.newFile("large.zip")
        FileOutputStream(zipFile).use { ZipUtils.zipFiles(listOf(src), it) }

        val dest = tempDir.newFolder("dest")
        FileInputStream(zipFile).use { ZipUtils.unzipFiles(it, dest) }

        val restored = File(File(dest, "src"), "large.txt")
        assertEquals(largeContent, restored.readText())
    }

    @Test
    fun zipFiles_preservesSpecialNames() {
        val src = tempDir.newFolder("config_backup_2024")
        File(src, "preferences.json").writeText("{}")
        File(src, "cache_data.bin").writeBytes(byteArrayOf(0, 1, 2, 3))

        val zipFile = tempDir.newFile("arirang_config_20240601_120000.zip")
        FileOutputStream(zipFile).use { ZipUtils.zipFiles(listOf(src), it) }

        val entries = readZipEntries(zipFile).sortedBy { it.name }
        assertEquals(2, entries.size)
        assertEquals("config_backup_2024/cache_data.bin", entries[0].name)
        assertEquals("config_backup_2024/preferences.json", entries[1].name)
    }

    data class ZipEntryInfo(val name: String, val content: String) {
        constructor(name: String, bytes: ByteArray) : this(name, String(bytes))
    }

    private fun readZipEntries(zipFile: File): List<ZipEntryInfo> {
        val entries = mutableListOf<ZipEntryInfo>()
        ZipInputStream(FileInputStream(zipFile)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val bytes = zis.readBytes()
                    entries.add(ZipEntryInfo(entry.name, bytes))
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return entries
    }
}
