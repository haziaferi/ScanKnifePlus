package com.haziaferi.scanknifeplus.scan

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.haziaferi.scanknifeplus.scanner.filter.DocumentFilters
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    // FileProvider matches roots by joining paths with '/', so on a Windows host Robolectric's backslash paths never match. CI (Linux) runs these,
    // and ScanFilesDeviceTest checks the same on a real device.
    private fun assumePosixPaths() = assumeTrue("FileProvider needs '/' paths", File.separatorChar == '/')

    @Test
    fun `files in the library and share folders get content URIs`() {
        assumePosixPaths()
        val page = File(ScanFiles.libraryDir(app), "doc/page.jpg").apply { parentFile!!.mkdirs(); writeText("x") }
        val uri = ScanFiles.contentUri(app, page)
        assertEquals("content", uri.scheme)
        assertEquals("com.haziaferi.scanknifeplus.fileprovider", uri.authority)
        assertEquals("/scans/doc/page.jpg", uri.path)

        val shared = File(ScanFiles.shareDir(app), "scan.pdf").apply { writeText("x") }
        assertEquals("/shared/scan.pdf", ScanFiles.contentUri(app, shared).path)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `files outside the two folders are not exposed`() {
        assumePosixPaths()
        ScanFiles.contentUri(app, File(app.filesDir, "signatures/sig.png").apply { parentFile!!.mkdirs(); writeText("x") })
    }

    @Test
    fun `camera permission is declared and checked`() {
        val info = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
        assertTrue(info.requestedPermissions!!.contains(Manifest.permission.CAMERA))
        assertFalse(CameraPermission.isGranted(app))
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        assertTrue(CameraPermission.isGranted(app))
    }

    @Test
    fun `the app can use scanner-core`() {
        assertEquals("Original", DocumentFilters.byName(null).name)
    }
}
