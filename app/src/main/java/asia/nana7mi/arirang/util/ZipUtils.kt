package asia.nana7mi.arirang.util

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object ZipUtils {
    fun zipFiles(directoriesToZip: List<File>, outputStream: OutputStream) {
        ZipOutputStream(outputStream).use { zos ->
            for (dir in directoriesToZip) {
                if (!dir.exists()) continue
                zipFile(dir, dir.name, zos)
            }
        }
    }

    private fun zipFile(fileToZip: File, fileName: String, zos: ZipOutputStream) {
        if (fileToZip.isHidden) return

        if (fileToZip.isDirectory) {
            val children = fileToZip.listFiles()
            if (children != null) {
                for (childFile in children) {
                    zipFile(childFile, "$fileName/${childFile.name}", zos)
                }
            }
            return
        }

        FileInputStream(fileToZip).use { fis ->
            val zipEntry = ZipEntry(fileName)
            zos.putNextEntry(zipEntry)
            val bytes = ByteArray(1024)
            var length: Int
            while (fis.read(bytes).also { length = it } >= 0) {
                zos.write(bytes, 0, length)
            }
            zos.closeEntry()
        }
    }

    fun unzipFiles(inputStream: InputStream, destinationDir: File) {
        ZipInputStream(inputStream).use { zis ->
            var zipEntry = zis.nextEntry
            val buffer = ByteArray(1024)
            while (zipEntry != null) {
                val newFile = File(destinationDir, zipEntry.name)
                // Vulnerability prevention: check for Zip Slip
                if (!newFile.canonicalPath.startsWith(destinationDir.canonicalPath + File.separator)) {
                    throw SecurityException("Entry is outside of the target dir: ${zipEntry.name}")
                }
                if (zipEntry.isDirectory) {
                    newFile.mkdirs()
                } else {
                    File(newFile.parent).mkdirs()
                    FileOutputStream(newFile).use { fos ->
                        var len: Int
                        while (zis.read(buffer).also { len = it } > 0) {
                            fos.write(buffer, 0, len)
                        }
                    }
                }
                zipEntry = zis.nextEntry
            }
            zis.closeEntry()
        }
    }
}
