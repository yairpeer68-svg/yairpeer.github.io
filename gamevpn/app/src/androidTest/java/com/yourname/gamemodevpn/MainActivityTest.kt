package com.yourname.gamemodevpn

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented UI tests — run on a real device or emulator.
 * Test the golden path: app opens, status shows, stats render.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun appLaunches_withoutCrash() {
        // If we reach here without exception, the app launched successfully
        onView(isRoot()).check(matches(isDisplayed()))
    }

    @Test
    fun statusLabel_isVisible() {
        // The status label should be visible on launch
        onView(withId(R.id.statusLabel)).check(matches(isDisplayed()))
    }

    @Test
    fun pingGraph_isVisible() {
        // The ping graph view should be present in the layout
        onView(withId(R.id.pingGraph)).check(matches(isDisplayed()))
    }
}
