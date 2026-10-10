package com.qq.closie

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Source-boundary guard, complementing behavior tests for the public navigation graph. */
class ArchitectureBoundaryTest {
    private val app = sequenceOf(File("."), File("app")).first { File(it, "src/main/java/com/qq/closie").isDirectory }
    private val source = File(app, "src/main/java/com/qq/closie")
    @Test fun financeOwnsTypedFactsInsideTheExistingDatabase() {
        val owner = File(source, "life/finance")
        owner.walkTopDown().filter { it.extension == "kt" }.forEach {
            val text = it.readText()
            assertFalse(it.name, text.contains("LifeEntityEntity"))
            assertFalse(it.name, text.contains("metadataJson"))
            assertFalse(it.name, text.contains("Room.databaseBuilder"))
        }
        File(source, "ui/lifeos/finance").walkTopDown().filter { it.extension == "kt" }.forEach {
            val text = it.readText()
            assertFalse(it.name, text.contains("financeDao("))
            assertFalse(it.name, text.contains("life.data.database"))
        }
        val allowedDeletes = setOf("finance_entry_tags", "finance_proposal_tags",
            "finance_rule_conditions", "finance_rule_actions")
        (File(source, "life/data/database/dao").listFiles()!!.filter { it.name.startsWith("Finance") } +
            File(source, "life/finance").walkTopDown().filter { it.extension == "kt" }.toList()).forEach { file ->
            val dao = file.readText()
            assertFalse(file.name, dao.contains("@Delete"))
            assertFalse(file.name, dao.contains("OnConflictStrategy.REPLACE"))
            Regex("DELETE\\s+FROM\\s+(finance_\\w+)", RegexOption.IGNORE_CASE).findAll(dao).forEach {
                assertTrue(file.name + ": " + it.value, it.groupValues[1].lowercase() in allowedDeletes)
            }
        }
        assertTrue(File(source, "life/data/database/LifeDatabase.kt").readText().contains("version = 4"))
        val version = Regex("version\\s*=\\s*(\\d+)").find(
            File(source, "life/data/database/LifeDatabase.kt").readText())!!.groupValues[1]
        val schema = File(app, "schemas/com.qq.closie.life.data.database.LifeDatabase/$version.json")
        assertTrue("Missing current Room/KSP schema: " + schema.path, schema.isFile)
        assertEquals(version.toInt(), com.google.gson.JsonParser.parseString(schema.readText())
            .asJsonObject.getAsJsonObject("database").get("version").asInt)
        assertEquals("life_os.db", com.qq.closie.life.data.database.LifeDatabase.DATABASE_NAME)
        assertEquals(com.qq.closie.navigation.LifeOsRoute.FINANCE,
            com.qq.closie.ui.lifeos.drawer.LifeModules.find("finance").route)
    }
    @Test fun financeListenerCannotWriteCanonicalTruthOrLaunchAnOverlay() {
        val listener = File(source, "life/finance/FinanceNotificationListener.kt").readText()
        assertTrue(listener.contains("financeAutomationRepository.discover(proposal)"))
        for (forbidden in listOf("financeDao(", "financeIntakeDao(", "saveEntry(", "saveTransfer(",
            "FinanceEntryEntity(", "FinanceRepository(", "startActivity(", "setFullScreenIntent", "TYPE_APPLICATION_OVERLAY")) {
            assertFalse(forbidden, listener.contains(forbidden))
        }
    }
    @Test fun captureHasNoUserLevelPhysicalDeleteApi() {
        val owners = listOf(
            com.qq.closie.life.repository.CaptureRepository::class.java,
            com.qq.closie.life.data.database.dao.CaptureDao::class.java,
            com.qq.closie.life.ui.capture.CaptureDetailViewModel::class.java,
            com.qq.closie.life.ui.capture.CaptureInboxViewModel::class.java
        )
        owners.forEach { owner -> assertFalse(owner.name, owner.declaredMethods.any { it.name == "delete" }) }
        assertTrue(com.qq.closie.life.data.database.dao.CaptureDao::class.java.declaredMethods.any { it.name == "deleteAll" })
        for (screen in listOf("CaptureDetailScreen.kt", "CaptureInboxScreen.kt")) {
            val ui = File(source, "life/ui/capture/$screen").readText()
            assertFalse(screen, ui.contains("onAskDelete"))
            assertFalse(screen, ui.contains("onDelete"))
            assertFalse(screen, ui.contains("删除后无法恢复"))
            assertFalse(screen, ui.contains(".dismiss("))
        }
    }
    @Test fun appearanceLoadingRendersBeforeBusinessStorageIsObtained() {
        val composition = File(source, "ui/lifeos/LifeOsApp.kt").readText()
        val loading = composition.indexOf("if (settings == null)")
        val container = composition.indexOf("val container = app.lifeContainer")
        assertTrue(loading >= 0 && container > loading)
        assertTrue(composition.indexOf("if (!ready)") < loading)
        val branch = composition.substring(loading, container)
        assertTrue(branch.contains("LifeOsTheme"))
        assertTrue(branch.contains("Surface(Modifier.fillMaxSize()"))
        assertTrue(branch.contains("正在准备生活页"))
        assertTrue(branch.contains("return"))
        assertFalse(composition.substring(0, container).contains("app.wardrobeRepository"))
        assertFalse(composition.contains("selected?.let"))
    }
    @Test fun migrationSchemaFixturesAreDebugOnly() {
        val gradle = File(app, "build.gradle.kts").readText()
        val fixtureSources = Regex("getByName\\(\"([^\"]+)\"\\)\\.assets\\.srcDir\\(\"\\\$projectDir/schemas\"\\)")
            .findAll(gradle).map { it.groupValues[1] }.toList()
        assertEquals(listOf("debug"), fixtureSources)
    }
    @Test fun thereIsOnlyOneProductRootAndNoObsoleteShell() {
        val activity = File(source, "MainActivity.kt").readText()
        assertTrue(activity.contains("LifeOsApp("))
        assertFalse(activity.contains("NavHost("))
        assertFalse(File(source, "life/ui/shell/LifeShell.kt").exists())
        assertEquals(1, source.walkTopDown().filter { it.extension == "kt" }
            .count { Regex("fun LifeOsRoot\\(").containsMatchIn(it.readText()) })
        assertFalse(File(app, "src/main/java/com/xiaoming/closie").exists())
        assertFalse(File(source, "navigation/ClosieNavigation.kt").readText().contains("ClosieHostMode"))
    }
    @Test fun projectionsCannotOwnDomainStorageOrDependOnClosetCalendar() {
        for (surface in listOf("home", "calendar", "map", "media")) {
            File(source, "ui/lifeos/$surface").walkTopDown().filter { it.extension == "kt" }.forEach {
                val imports = it.readLines().filter { line -> line.startsWith("import ") }.joinToString("\n")
                assertFalse(it.name, imports.contains("life.data.database"))
                assertFalse(it.name, imports.contains("WardrobeRepository"))
                assertFalse(it.name, imports.contains("ui.ootd"))
                if (surface != "home") assertFalse(it.name, imports.contains("ui.lifeos.home"))
            }
        }
    }
    @Test fun galleryCopyBackendIsNotExposedByTheCompositionRoot() {
        assertFalse(File(source, "life/data/LifeContainer.kt").readText().contains("MediaStoreImporter"))
        assertFalse(File(source, "life/ui/capture/CaptureBottomSheet.kt").readText().contains("FromGallery"))
        assertFalse(File(source, "life/reference/ReferenceImporter.kt").readText().contains("importGalleryImage"))
    }
    @Test fun debugIdentityCannotOccupyTheReleaseUpdateChain() {
        assertEquals(if (BuildConfig.DEBUG) "com.qqhome.lifeos.debug" else "com.qqhome.lifeos", BuildConfig.APPLICATION_ID)
        val gradle = File(app, "build.gradle.kts").readText()
        assertTrue(gradle.contains("namespace = \"com.qq.closie\""))
        assertTrue(gradle.contains("applicationId = \"com.qqhome.lifeos\""))
        val debug = gradle.substringAfter("debug {").substringBefore("release {")
        assertFalse(debug.contains("signingConfig ="))
    }
}
