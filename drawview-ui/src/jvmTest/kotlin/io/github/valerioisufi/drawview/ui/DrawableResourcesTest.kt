package io.github.valerioisufi.drawview.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DrawableResourcesTest {

    @Test
    fun testAllVectorDrawablesHaveValidHexColors() {
        val drawableDir = File("src/commonMain/composeResources/drawable")
        val altDir = File("drawview-ui/src/commonMain/composeResources/drawable")
        val dir = if (drawableDir.exists()) drawableDir else altDir

        assertTrue(dir.exists(), "Drawable directory must exist: ${dir.absolutePath}")

        val xmlFiles = dir.listFiles { _, name -> name.endsWith(".xml") } ?: emptyArray()
        assertTrue(xmlFiles.isNotEmpty(), "There should be XML drawable files to test")

        for (file in xmlFiles) {
            val content = file.readText()
            assertFalse(
                content.contains("@android:color"),
                "File ${file.name} must not contain @android:color, found in: $content"
            )
            assertFalse(
                content.contains("?attr/"),
                "File ${file.name} must not contain ?attr/, found in: $content"
            )
            assertFalse(
                content.contains("@color/"),
                "File ${file.name} must not contain @color/, found in: $content"
            )
        }
    }
}
