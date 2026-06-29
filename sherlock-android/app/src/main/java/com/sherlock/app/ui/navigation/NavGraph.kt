package com.sherlock.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.sherlock.app.data.model.AppTheme
import com.sherlock.app.data.model.SearchType
import com.sherlock.app.ui.screens.*

object Routes {
    const val SPLASH = "splash"
    const val HOME = "home"
    const val FACE_SEARCH = "face_search"
    const val USERNAME_SEARCH = "username_search"
    const val EMAIL_SEARCH = "email_search"
    const val PHONE_SEARCH = "phone_search"
    const val FACE_COMPARE = "face_compare"
    const val GOOGLE_DORK = "google_dork"
    const val EXIF_VIEWER = "exif_viewer"
    const val BREACH_CHECK = "breach_check"
    const val DOMAIN_LOOKUP = "domain_lookup"
    const val HISTORY = "history"
    const val FAVORITES = "favorites"
    const val STATISTICS = "statistics"
    const val SETTINGS = "settings"
    const val MONITOR = "monitor"
}

@Composable
fun SherlockNavGraph(
    navController: NavHostController,
    onThemeChange: (AppTheme) -> Unit
) {
    NavHost(navController = navController, startDestination = Routes.SPLASH) {

        composable(Routes.SPLASH) {
            SplashScreen { navController.navigate(Routes.HOME) { popUpTo(Routes.SPLASH) { inclusive = true } } }
        }

        composable(Routes.HOME) {
            HomeScreen(
                onNavigateToFaceSearch = { navController.navigate(Routes.FACE_SEARCH) },
                onNavigateToUsernameSearch = { navController.navigate(Routes.USERNAME_SEARCH) },
                onNavigateToEmailSearch = { navController.navigate(Routes.EMAIL_SEARCH) },
                onNavigateToPhoneSearch = { navController.navigate(Routes.PHONE_SEARCH) },
                onNavigateToGoogleDork = { navController.navigate(Routes.GOOGLE_DORK) },
                onNavigateToFaceCompare = { navController.navigate(Routes.FACE_COMPARE) },
                onNavigateToBreachCheck = { navController.navigate(Routes.BREACH_CHECK) },
                onNavigateToExifViewer = { navController.navigate(Routes.EXIF_VIEWER) },
                onNavigateToHistory = { navController.navigate(Routes.HISTORY) },
                onNavigateToFavorites = { navController.navigate(Routes.FAVORITES) },
                onNavigateToStatistics = { navController.navigate(Routes.STATISTICS) },
                onNavigateToSettings = { navController.navigate(Routes.SETTINGS) },
                onNavigateToMonitor = { navController.navigate(Routes.MONITOR) },
                onNavigateToDomainLookup = { navController.navigate(Routes.DOMAIN_LOOKUP) }
            )
        }

        composable(Routes.FACE_SEARCH) {
            FaceSearchScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.USERNAME_SEARCH) {
            UsernameSearchScreen(onNavigateBack = { navController.popBackStack() }, searchType = SearchType.USERNAME)
        }

        composable(Routes.EMAIL_SEARCH) {
            UsernameSearchScreen(onNavigateBack = { navController.popBackStack() }, searchType = SearchType.EMAIL)
        }

        composable(Routes.PHONE_SEARCH) {
            UsernameSearchScreen(onNavigateBack = { navController.popBackStack() }, searchType = SearchType.PHONE)
        }

        composable(Routes.FACE_COMPARE) {
            FaceCompareScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.GOOGLE_DORK) {
            GoogleDorkScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.EXIF_VIEWER) {
            ExifViewerScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.BREACH_CHECK) {
            BreachCheckScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.DOMAIN_LOOKUP) {
            DomainLookupScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.HISTORY) {
            HistoryScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.FAVORITES) {
            FavoritesScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.STATISTICS) {
            StatisticsScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onThemeChange = onThemeChange
            )
        }

        composable(Routes.MONITOR) {
            MonitorScreen(onNavigateBack = { navController.popBackStack() })
        }
    }
}
