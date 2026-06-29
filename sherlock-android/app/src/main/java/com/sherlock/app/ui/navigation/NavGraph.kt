package com.sherlock.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.sherlock.app.ui.screens.FaceSearchScreen
import com.sherlock.app.ui.screens.HomeScreen
import com.sherlock.app.ui.screens.UsernameSearchScreen

object Routes {
    const val HOME = "home"
    const val FACE_SEARCH = "face_search"
    const val USERNAME_SEARCH = "username_search"
}

@Composable
fun SherlockNavGraph(navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = Routes.HOME
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                onNavigateToFaceSearch = { navController.navigate(Routes.FACE_SEARCH) },
                onNavigateToUsernameSearch = { navController.navigate(Routes.USERNAME_SEARCH) }
            )
        }

        composable(Routes.FACE_SEARCH) {
            FaceSearchScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Routes.USERNAME_SEARCH) {
            UsernameSearchScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
