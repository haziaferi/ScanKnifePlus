package com.haziaferi.scanknifeplus.scan

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ScanFilesTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `library and share folders live in app-private storage`() {
        assertEquals(File(app.filesDir, "scans"), ScanFiles.libraryDir(app))
        assertEquals(File(app.cacheDir, "shared"), ScanFiles.shareDir(app))
        assertTrue(ScanFiles.libraryDir(app).isDirectory)
    }

    @Test
    fun `the cache folder names are the three cache folders, and reading them creates nothing`() {
        app.cacheDir.listFiles().orEmpty().forEach { it.deleteRecursively() }
        val names = ScanFiles.CACHE_FOLDERS
        assertTrue(app.cacheDir.list().orEmpty().isEmpty())
        val dirs = listOf(ScanFiles.shareDir(app), ScanFiles.stagingDir(app), ScanFiles.exportDir(app))
        dirs.forEach { assertEquals(app.cacheDir, it.parentFile) }
        assertEquals(dirs.map { it.name }.toSet(), names)
    }

    // FileProvider matches roots by joining paths with '/', so on a Windows host Robolectric's backslash paths never match. CI (Linux) runs these,
    // and ScanFilesDeviceTest checks the same on a real device.
    private fun assumePosixPaths() = assumeTrue("FileProvider needs '/' paths", File.separatorChar == '/')

    @Test
    fun `files in the share folder get content URIs`() {
        assumePosixPaths()
        val shared = File(ScanFiles.shareDir(app), "scan.pdf").apply { writeText("x") }
        val uri = ScanFiles.contentUri(app, shared)
        assertEquals("content", uri.scheme)
        assertEquals("com.haziaferi.scanknifeplus.fileprovider", uri.authority)
        assertEquals("/shared/scan.pdf", uri.path)
    }

    @Test
    fun `files outside the share folder are not exposed`() {
        assumePosixPaths()
        val outside = listOf(
            File(ScanFiles.libraryDir(app), "doc/page.jpg"),
            File(app.filesDir, "signatures/sig.png"),
            File(app.cacheDir, "shared2/x.txt"), // shares the exposed "shared" prefix but is a different folder
            File(app.cacheDir, "other.txt"),
        )
        for (f in outside) {
            f.parentFile!!.mkdirs()
            f.writeText("x")
            try {
                ScanFiles.contentUri(app, f)
                fail("$f must not get a content URI")
            } catch (expected: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun `camera permission is declared and checked`() {
        val info = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
        assertTrue(info.requestedPermissions!!.contains(Manifest.permission.CAMERA))
        assertFalse(CameraPermission.isGranted(app))
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        assertTrue(CameraPermission.isGranted(app))
    }
}
