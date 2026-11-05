package com.clouddroid.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("home", "Home", Icons.Filled.Home),
    Tab("library", "Library", Icons.Filled.Apps),
    Tab("cloud", "Cloud", Icons.Filled.Cloud),
    Tab("device", "Device", Icons.Filled.Dns),
    Tab("settings", "Settings", Icons.Filled.Settings),
)

@Composable
fun AppRoot(vm: AppViewModel = viewModel()) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route.orEmpty()
    val fullscreen = route.startsWith("player")
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(vm.message) {
        vm.message?.let { snackbar.showSnackbar(it); vm.toast(null) }
    }

    fun tab(r: String) = nav.navigate(r) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            AnimatedVisibility(visible = !fullscreen && !route.startsWith("browse") && route != "stream") {
                NavigationBar {
                    for (t in TABS) NavigationBarItem(
                        selected = route == t.route || (t.route == "library" && route == "add"),
                        onClick = { tab(t.route) },
                        icon = { Icon(t.icon, t.label) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { pad ->
        NavHost(nav, startDestination = "home", modifier = Modifier.padding(if (fullscreen) androidx.compose.foundation.layout.PaddingValues() else pad)) {
            composable("home") {
                HomeScreen(vm,
                    onAdd = { nav.navigate("add") },
                    onLaunch = { nav.navigate("player/$it") },
                    onOpenDevice = { tab("device") },
                    onOpenCloud = { tab("cloud") },
                    onOpenSettings = { tab("settings") })
            }
            composable("library") {
                LibraryScreen(vm, onLaunch = { nav.navigate("player/$it") }, onUpdate = { nav.navigate("add") }, onOpenSettings = { tab("settings") })
            }
            composable("add") {
                AddScreen(vm, onPick = { nav.navigate("browse/$it?pick=true") }, onOpenSettings = { tab("settings") })
            }
            composable("cloud") {
                CloudStorageScreen(vm, onBrowse = { nav.navigate("browse/$it?pick=false") })
            }
            composable("device") { DeviceScreen(vm, onOpenSettings = { tab("settings") }) }
            composable("settings") { SettingsScreen(vm, onOpenLibrary = { tab("library") }) }
            composable(
                "browse/{provider}?pick={pick}",
                arguments = listOf(
                    navArgument("provider") { type = NavType.StringType },
                    navArgument("pick") { type = NavType.BoolType; defaultValue = false },
                ),
            ) { e ->
                val provider = e.arguments?.getString("provider") ?: "terabox"
                val pick = e.arguments?.getBoolean("pick") ?: false
                BrowserScreen(vm, provider, pick,
                    onBack = { nav.popBackStack() },
                    onStream = { entry -> vm.streamFile(provider, entry); nav.navigate("stream") },
                    onInstall = { p, path ->
                        vm.install(p, path)
                        nav.navigate("add") { popUpTo("add") { inclusive = true } }
                    })
            }
            composable("stream") { StreamScreen(vm, onExit = { nav.popBackStack() }) }
            composable(
                "player/{pkg}",
                arguments = listOf(navArgument("pkg") { type = NavType.StringType }),
            ) { e ->
                PlayerScreen(vm, e.arguments?.getString("pkg").orEmpty(), onExit = { nav.popBackStack() })
            }
        }
    }
}
