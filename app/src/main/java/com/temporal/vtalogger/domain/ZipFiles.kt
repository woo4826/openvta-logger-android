package com.temporal.vtalogger.domain

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ZipFiles {
    fun zipSingleFile(source: File, target: File): File {
        require(source.isFile) { "Source must be a file: ${source.absolutePath}" }
        target.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(target)).use { zip ->
            zip.putNextEntry(ZipEntry(source.name))
            FileInputStream(source).use { input -> input.copyTo(zip) }
            zip.closeEntry()
        }
        return target
    }
}
