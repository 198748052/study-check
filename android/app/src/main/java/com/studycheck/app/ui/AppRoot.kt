package com.studycheck.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.studycheck.app.ui.theme.Bg
import com.studycheck.app.ui.theme.Brand
import com.studycheck.app.ui.theme.Muted

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("today", "今日", Icons.Filled.Home),
    Tab("history", "历史", Icons.Filled.DateRange),
    Tab("stats", "统计", Icons.Filled.Star),
    Tab("me", "我的", Icons.Filled.Person),
)

@Composable
fun AppRoot(appViewModel: AppViewModel = hiltViewModel()) {
    val nav = rememberNavController()
    val loggedIn by appViewModel.loggedIn.collectAsStateWithLifecycle()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val showBar = route != null && TABS.any { it.route == route }

    LaunchedEffect(loggedIn, route) {
        when {
            !loggedIn && route != null && route != "login" ->
                nav.navigate("login") {
                    popUpTo(0) { inclusive = true }
                    launchSingleTop = true
                }
            loggedIn && route == "login" ->
                nav.navigate("today") {
                    popUpTo(0) { inclusive = true }
                    launchSingleTop = true
                }
        }
    }

    Scaffold(
        containerColor = Bg,
        bottomBar = {
            if (showBar) {
                NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
                    TABS.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = {
                                if (route != tab.route) {
                                    nav.navigate(tab.route) {
                                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label, fontSize = 10.sp) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Brand,
                                selectedTextColor = Brand,
                                indicatorColor = Brand.copy(alpha = 0.12f),
                                unselectedIconColor = Muted,
                                unselectedTextColor = Muted,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = "today",
            modifier = Modifier.padding(padding),
        ) {
            composable("login") {
                LoginScreen(
                    onLoggedIn = { appViewModel.signIn() },
                    onSkipLogin = { appViewModel.signInDev() },
                )
            }
            composable("today") {
                TodayScreen(
                    onOpenCamera = { kind -> nav.navigate("camera/$kind") },
                    onOpenDetail = { nav.navigate("detail") },
                )
            }
            // 二级页面：当天详情（连拍 + 笔记）
            composable("detail") {
                DayDetailScreen(
                    onBack = { nav.popBackStack() },
                    onOpenBurst = { nav.navigate("burst") },
                )
            }
            composable("burst") {
                BurstCameraScreen(onClose = { nav.popBackStack() })
            }
            composable("camera/{kind}") { entry ->
                val kind = entry.arguments?.getString("kind") ?: "in"
                CameraScreen(
                    kind = kind,
                    onClose = { nav.popBackStack() },
                )
            }
            composable("history") {
                HistoryScreen(onOpenDay = { day -> nav.navigate("archive/$day") })
            }
            // 历史某天详情：打卡照片 + 备注 + 当天过程留存
            composable("archive/{day}") {
                DayArchiveScreen(onBack = { nav.popBackStack() })
            }
            composable("stats") { StatsScreen() }
            composable("me") { MeScreen(onSignedOut = { appViewModel.signOut() }) }
        }
    }
}
