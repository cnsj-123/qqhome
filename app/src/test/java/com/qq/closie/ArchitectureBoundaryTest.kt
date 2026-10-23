package com.qq.closie

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Source-boundary guard, complementing behavior tests for the public navigation graph. */
class ArchitectureBoundaryTest {
    private val app = sequenceOf(File("."), File("app")).first { File(it, "src/main/java/com/qq/closie").isDirectory }
    private val source = File(app, "src/main/java/com/qq/closie")
    private val allowedDeletes = setOf("finance_entry_tags", "finance_proposal_tags",
        "finance_rule_conditions", "finance_rule_actions", "finance_draft_tags",
        "finance_evidence_proposals", "finance_grant_events", "finance_draft_expected", "finance_grant_entries", "finance_review_expected")
    private val economicDraftChildren = setOf("finance_economic_draft_funding", "finance_economic_draft_shares")
    private fun financeWriteViolations(text:String):List<String> {
        val failures=mutableListOf<String>()
        if(Regex("@Delete\\b").containsMatchIn(text)) failures+="@Delete"
        if(text.contains("OnConflictStrategy.REPLACE")) failures+="REPLACE"
        val queries=Regex("@Query\\(\\s*\"([^\"]+)\"\\s*\\)").findAll(text).toList()
        Regex("DELETE\\s+FROM\\s+[`\"]?(finance_\\w+)",RegexOption.IGNORE_CASE).findAll(text).forEach { deletion ->
            val table=deletion.groupValues[1].lowercase()
            if(table in economicDraftChildren) {
                // These are mutable editor associations, not canonical funding or shares.
                // Permit only the four actual DAO queries used by draft save / atomic restore.
                val query=queries.singleOrNull {deletion.range.first in it.range}?.groupValues?.get(1)
                    ?.trim()?.replace(Regex("\\s+")," ")
                val approved=listOf("DELETE FROM $table","DELETE FROM $table WHERE draftId = :id")
                if(approved.none {it.equals(query,ignoreCase=true)}) failures+=deletion.value
            } else if(table !in allowedDeletes) failures+=deletion.value
        }
        return failures
    }
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
        (File(source, "life/data/database/dao").listFiles()!!.filter { it.name.startsWith("Finance") } +
            File(source, "life/finance").walkTopDown().filter { it.extension == "kt" }.toList()).forEach { file ->
            val dao = file.readText()
            assertEquals(file.name,emptyList<String>(),financeWriteViolations(dao))
        }
        assertTrue(File(source, "life/data/database/LifeDatabase.kt").readText().contains("version = 5"))
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
    @Test fun economicDraftDeleteAllowanceIsLimitedToApprovedAssociationQueries() {
        for(table in economicDraftChildren) {
            assertTrue(financeWriteViolations("@Query(\"DELETE FROM $table\")").isEmpty())
            assertTrue(financeWriteViolations("@Query(\"DELETE FROM $table WHERE draftId = :id\")").isEmpty())
            assertFalse(financeWriteViolations("@Query(\"DELETE FROM $table WHERE revision < :revision\")").isEmpty())
            assertFalse(financeWriteViolations("execSQL(\"DELETE FROM $table\")").isEmpty())
        }
    }
    @Test fun permanentCanonicalDeletesAndReplaceRemainRejected() {
        for(table in listOf("finance_accounts","finance_entries","finance_transfers","finance_events",
            "finance_event_entries","finance_economics","finance_funding","finance_shares","finance_economic_drafts")) {
            for(sql in listOf("DELETE FROM $table","DELETE FROM `$table` WHERE id = :id","delete from $table"))
                assertFalse(sql,financeWriteViolations("@Query(\"$sql\")").isEmpty())
        }
        assertFalse(financeWriteViolations("@Delete suspend fun erase(row: FinanceEntryEntity)").isEmpty())
        assertFalse(financeWriteViolations("@Insert(onConflict = OnConflictStrategy.REPLACE)").isEmpty())
    }
    @Test fun financeListenerCannotWriteCanonicalTruthOrLaunchAnOverlay() {
        val listener = File(source, "life/finance/FinanceNotificationListener.kt").readText()
        assertTrue(listener.contains("financeAutomationRepository"))
        assertTrue(Regex("""\.discoverWithResult\s*\(\s*proposal\b""").containsMatchIn(listener))
        for (forbidden in listOf("financeDao(", "financeIntakeDao(", "saveEntry(", "saveTransfer(",
            "FinanceEntryEntity(", "FinanceRepository(", "startActivity(", "setFullScreenIntent", "TYPE_APPLICATION_OVERLAY",
            "financeAutomationRepository.confirm(", "FinanceReceiptOverlay")) {
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
        val container = composition.indexOf("val container = resources.first")
        assertTrue(loading >= 0 && container > loading)
        assertTrue(composition.indexOf("if (!ready)") < loading)
        val branch = composition.substring(loading, container)
        assertTrue(branch.contains("LifeOsTheme"))
        assertTrue(branch.contains("LifeStartupFeedback"))
        val feedback=File(source,"ui/lifeos/LifeStartupFeedback.kt").readText()
        assertTrue(feedback.contains("Surface(Modifier.fillMaxSize()"))
        assertFalse(feedback.contains("app.lifeContainer"))
        assertFalse(feedback.contains("app.wardrobeRepository"))
        assertTrue(branch.contains("正在准备生活页"))
        assertTrue(branch.contains("return"))
        assertFalse(composition.substring(0, container).contains("app.wardrobeRepository"))
        assertFalse(composition.contains("selected?.let"))
        val loader=File(source,"ui/lifeos/LifeShellResources.kt").readText()
        assertTrue(loader.contains("withContext(Dispatchers.IO)"))
        assertTrue(loader.contains("app.lifeContainer to app.wardrobeRepository"))
        assertTrue(composition.indexOf("loadLifeShellResources(app)")>loading)
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
