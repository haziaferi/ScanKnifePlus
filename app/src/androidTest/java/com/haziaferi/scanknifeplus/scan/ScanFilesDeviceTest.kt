package com.haziaferi.scanknifeplus.scan

import android.Manifest
import android.content.pm.FeatureInfo
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** The FileProvider and camera set-up on a real device (run with `./gradlew connectedDebugAndroidTest`). */
@RunWith(AndroidJUnit4::class)
class ScanFilesDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val created = mutableListOf<File>()

    private fun file(dir: File, path: String, text: String = "x") =
        File(dir, path).apply { parentFile!!.mkdirs(); writeText(text) }.also { created += it }

    @After
    fun cleanUp() {
        created.forEach { it.delete() }
        listOf(File(ScanFiles.libraryDir(context), "doc"), File(context.filesDir, "scans2")).forEach { it.deleteRecursively() }
    }

    @Test
    fun libraryAndShareFilesGetReadableContentUris() {
        val uri = ScanFiles.contentUri(context, file(ScanFiles.libraryDir(context), "doc/page.jpg"))
        assertEquals("content", uri.scheme)
        assertEquals("/scans/doc/page.jpg", uri.path)
        // Reading through the resolver proves the manifest authority and paths, not just the URI string.
        context.contentResolver.openInputStream(uri)!!.use { assertEquals("x", it.readBytes().decodeToString()) }

        val shared = ScanFiles.contentUri(context, file(ScanFiles.shareDir(context), "scan.pdf", "y"))
        assertEquals("/shared/scan.pdf", shared.path)
        context.contentResolver.openInputStream(shared)!!.use { assertEquals("y", it.readBytes().decodeToString()) }
    }

    @Test
    fun filesOutsideTheTwoFoldersAreNotExposed() {
        val outside = listOf(
            file(context.filesDir, "outside.txt"),
            file(context.filesDir, "scans2/x.txt"), // shares the "scans" prefix but is a different folder
            file(context.cacheDir, "other.txt"),
        )
        for (f in outside) {
            try {
                ScanFiles.contentUri(context, f)
                fail("$f must not get a content URI")
            } catch (expected: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun cameraPermissionIsDeclaredAndEveryCameraFeatureIsOptional() {
        val pm = context.packageManager
        val info = pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS or PackageManager.GET_CONFIGURATIONS)
        assertTrue(info.requestedPermissions!!.contains(Manifest.permission.CAMERA))
        val camera = info.reqFeatures.orEmpty().filter { it.name?.startsWith("android.hardware.camera") == true }
        assertEquals(
            setOf("android.hardware.camera.any", "android.hardware.camera", "android.hardware.camera.autofocus"),
            camera.map { it.name }.toSet(),
        )
        camera.forEach { assertFalse("${it.name} must be optional", it.flags and FeatureInfo.FLAG_REQUIRED != 0) }
    }
}
