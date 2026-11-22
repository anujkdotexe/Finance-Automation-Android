package com.financeautomation.presentation.screens.drawer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Source level checks that the Remove actions are wired end to end and follow the component standard. */
class PeopleTripsRemoveWiringTest {

    private val screen = File("src/main/java/com/financeautomation/presentation/screens/drawer/PeopleTripsScreen.kt").readText()
    private val detailSheet = File("src/main/java/com/financeautomation/presentation/screens/drawer/TripDetailSheet.kt").readText()
    private val coordinator = File("src/main/java/com/financeautomation/presentation/viewmodel/SettingsCustomizationCoordinator.kt").readText()
    private val viewModel = File("src/main/java/com/financeautomation/presentation/viewmodel/SettingsViewModel.kt").readText()

    @Test
    fun personRowHasAnOverflowRemoveThatAsksFirst() {
        assertTrue(screen.contains("Icons.Rounded.MoreVert"))
        assertTrue(screen.contains("R.string.people_remove_cd"))
        assertTrue(screen.contains("onRemove = { personToRemove = companion }"))
        assertTrue("removal happens only after the dialog", screen.contains("viewModel.onRemoveCompanion(person.id)"))
        assertEquals(1, "viewModel.onRemoveCompanion(person.id)".toRegex(RegexOption.LITERAL).findAll(screen).count())
    }

    @Test
    fun onlyAnInactiveTripOffersRemoveInTheDetailSheet() {
        // The trip cards no longer carry a Remove action; removal moved into the trip detail sheet
        val cardBody = screen.substringAfter("private fun TripCard(").substringAfter("val removeTripDescription").substringBefore("\nprivate fun ")
        assertFalse("the trip card must not call onRemove", cardBody.contains("onRemove"))

        val actions = detailSheet.substringAfter("// Action Buttons")
        val active = actions.substringAfter("if (isActive) {").substringBefore("} else {")
        assertTrue("an active trip shows Delete as a disabled button", active.contains("enabled = false"))
        assertTrue(active.contains("R.string.trip_delete_active_blocked"))
        assertFalse("an active trip never opens the delete dialog", active.contains("showDeleteConfirm = true"))
        assertFalse(active.contains("onDeleteTrip"))

        val inactive = actions.substringAfter("} else {")
        assertTrue("an ended trip opens the delete dialog", inactive.contains("showDeleteConfirm = true"))
        assertTrue("the trip is deleted only from the dialog confirm", detailSheet.substringAfter("if (showDeleteConfirm) {").contains("onDeleteTrip(summary.name)"))
        assertEquals(1, "onDeleteTrip(summary.name)".toRegex(RegexOption.LITERAL).findAll(detailSheet).count())
        assertTrue(screen.contains("viewModel.onRemoveTripTag(tripName)"))
    }

    @Test
    fun theCallsGoThroughTheRepositoryApiAndRefusalsAreShown() {
        assertTrue(coordinator.contains("peopleRepository.removePerson"))
        assertTrue(coordinator.contains("repo.removeTripTag(name)"))
        assertTrue(viewModel.contains("fun onRemoveTripTag"))
        assertTrue(screen.contains("SnackbarHost("))
        assertTrue(screen.contains("snackbarHostState.showSnackbar(it.asString(context))"))
    }

    @Test
    fun theDialogFollowsTheComponentStandard() {
        val dialog = screen.substringAfter("private fun ConfirmRemoveDialog(")
        assertTrue(dialog.indexOf("dismissButton") >= 0 && dialog.indexOf("confirmButton") >= 0)
        assertTrue(dialog.substringAfter("dismissButton").substringBefore("confirmButton").contains("OutlinedButton"))
        assertTrue(dialog.substringAfter("confirmButton").contains("Button("))
        assertEquals(2, Regex("minHeight = FinanceTheme.sizes.minTouchTarget").findAll(dialog).count())
    }

    @Test
    fun textsComeFromStringsAndThereAreNoEmojis() {
        val strings = File("src/main/res/values/strings.xml").readText()
        for (name in listOf("people_remove_title", "people_remove_message", "trips_remove_title", "trips_remove_message", "people_remove_cd")) {
            assertTrue(name, strings.contains("name=\"$name\""))
        }
        assertFalse(screen.any { it.code in 0x1F300..0x1FAFF })
    }
}
