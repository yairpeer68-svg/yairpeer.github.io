package com.sherlock.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.sherlock.app.data.model.SearchType
import com.sherlock.app.ui.screens.FaceSearchScreen
import com.sherlock.app.ui.screens.GoogleDorkScreen
import com.sherlock.app.ui.screens.HomeScreen
import com.sherlock.app.ui.screens.UsernameSearchScreen

object Routes {
    const val HOME = "home"
    const val USERNAME_SEARCH = "username_search"
    const val EMAIL_SEARCH = "email_search"
    const val FACE_SEARCH = "face_search"
    const val GOOGLE_DORK = "google_dork"
}

@Composable
fun SherlockNavGraph(navController: NavHostController) {
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onNavigateToUsernameSearch = { navController.navigate(Routes.USERNAME_SEARCH) },
                onNavigateToEmailSearch = { navController.navigate(Routes.EMAIL_SEARCH) },
                onNavigateToFaceSearch = { navController.navigate(Routes.FACE_SEARCH) },
                onNavigateToGoogleDork = { navController.navigate(Routes.GOOGLE_DORK) }
            )
        }
        composable(Routes.USERNAME_SEARCH) {
            UsernameSearchScreen(
                onNavigateBack = { navController.popBackStack() },
                searchType = SearchType.USERNAME
            )
        }
        composable(Routes.EMAIL_SEARCH) {
            UsernameSearchScreen(
                onNavigateBack = { navController.popBackStack() },
                searchType = SearchType.EMAIL
            )
        }
        composable(Routes.FACE_SEARCH) {
            FaceSearchScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(Routes.GOOGLE_DORK) {
            GoogleDorkScreen(onNavigateBack = { navController.popBackStack() })
        }
    }
}
