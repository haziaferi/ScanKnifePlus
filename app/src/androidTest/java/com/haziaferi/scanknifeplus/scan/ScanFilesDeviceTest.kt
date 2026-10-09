package com.haziaferi.scanknifeplus.scan

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The FileProvider and permission set-up on a real device (run with `./gradlew connectedDebugAndroidTest`). */
@RunWith(AndroidJUnit4::class)
class ScanFilesDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun libraryAndShareFilesGetContentUris() {
        val page = File(ScanFiles.libraryDir(context), "doc/page.jpg").apply { parentFile!!.mkdirs(); writeText("x") }
        val uri = ScanFiles.contentUri(context, page)
        assertEquals("content", uri.scheme)
        assertEquals("${context.packageName}.fileprovider", uri.authority)
        assertEquals("/scans/doc/page.jpg", uri.path)
        context.contentResolver.openInputStream(uri)!!.use { assertEquals("x", it.readBytes().decodeToString()) }

        val shared = File(ScanFiles.shareDir(context), "scan.pdf").apply { writeText("y") }
        assertEquals("/shared/scan.pdf", ScanFiles.contentUri(context, shared).path)
        page.parentFile!!.deleteRecursively()
        shared.delete()
    }

    @Test(expected = IllegalArgumentException::class)
    fun filesOutsideTheTwoFoldersAreNotExposed() {
        ScanFiles.contentUri(context, File(context.filesDir, "outside.txt").apply { writeText("x") })
    }

    @Test
    fun cameraPermissionIsDeclared() {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        assertTrue(info.requestedPermissions!!.contains(Manifest.permission.CAMERA))
    }
}
