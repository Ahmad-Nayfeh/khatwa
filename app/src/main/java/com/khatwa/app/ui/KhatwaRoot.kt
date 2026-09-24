package com.khatwa.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.khatwa.app.AppContainer
import com.khatwa.app.i18n.Strings
import com.khatwa.app.i18n.strings
import com.khatwa.app.settings.Settings
import com.khatwa.app.ui.home.HomeScreen
import com.khatwa.app.ui.onboarding.OnboardingScreen
import com.khatwa.app.ui.settings.SettingsNav
import com.khatwa.app.ui.stats.StatsScreen

sealed class Tab(val route: String, val label: (Strings) -> String, val icon: ImageVector, val tag: String) {
    data object Home : Tab("home", { it.tabHome }, Icons.Filled.Home, "tab_home")
    data object Stats : Tab("stats", { it.tabStats }, Icons.Filled.BarChart, "tab_stats")
    data object Groups : Tab("groups", { it.tabGroups }, Icons.Filled.Groups, "tab_groups")
    data object Settings : Tab("settings", { it.tabSettings }, Icons.Filled.Settings, "tab_settings")
}

private val tabs = listOf(Tab.Home, Tab.Stats, Tab.Groups, Tab.Settings)

@Composable
fun KhatwaRoot(container: AppContainer, settings: Settings) {
    // "Restore the copy from your account?" / "your data is back": over any screen.
    com.khatwa.app.ui.account.CloudBackupDialogs(container)
    if (!settings.onboardingDone) {
        OnboardingScreen(container)
        return
    }
    val s = strings
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination
    val showBar = tabs.any { t -> current?.hierarchy?.any { it.route == t.route } == true }

    Scaffold(
        bottomBar = {
            if (showBar) NavigationBar {
                tabs.forEach { tab ->
                    val selected = current?.hierarchy?.any { it.route == tab.route } == true
                    val label = tab.label(s)
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            nav.navigate(tab.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = label) },
                        label = { Text(label) },
                        modifier = Modifier.testTag(tab.tag),
                    )
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Tab.Home.route, modifier = Modifier.padding(padding)) {
            composable(Tab.Home.route) { HomeScreen(container, onOpenSettings = { nav.navigate(Tab.Settings.route) }) }
            composable(Tab.Stats.route) { StatsScreen(container) }
            composable(Tab.Groups.route) { com.khatwa.app.ui.groups.GroupsScreen(container) }
            composable(Tab.Settings.route) { SettingsNav(container) }
        }
    }
}
