package com.cardlens.tcg

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material.icons.outlined.CenterFocusWeak
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.ViewModule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.cardlens.tcg.data.ThemeMode
import com.cardlens.tcg.model.TcgCard
import com.cardlens.tcg.ui.collection.CollectionScreen
import com.cardlens.tcg.ui.decks.DeckDetailScreen
import com.cardlens.tcg.ui.decks.DecksScreen
import com.cardlens.tcg.ui.detail.CardDetailScreen
import com.cardlens.tcg.ui.scanner.ScannerScreen
import com.cardlens.tcg.ui.search.SearchScreen
import com.cardlens.tcg.ui.settings.SettingsScreen
import com.cardlens.tcg.ui.theme.CardLensTheme
import com.cardlens.tcg.ui.trade.TradeScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val app = LocalContext.current.applicationContext as CardLensApp
            val themeMode by app.container.settings.themeMode.collectAsState()
            val dynamicColor by app.container.settings.dynamicColor.collectAsState()
            val darkTheme = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
            }
            CardLensTheme(darkTheme = darkTheme, dynamicColor = dynamicColor) {
                CardLensNavHost()
            }
        }
    }
}

private data class BottomItem(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val icon: ImageVector
)

private val bottomItems = listOf(
    BottomItem("collection", "Sammlung", Icons.Filled.Style, Icons.Outlined.Style),
    BottomItem("decks", "Decks", Icons.Filled.ViewModule, Icons.Outlined.ViewModule),
    BottomItem("scan", "Scannen", Icons.Filled.CenterFocusStrong, Icons.Outlined.CenterFocusWeak),
    BottomItem("search", "Suche", Icons.Filled.Search, Icons.Outlined.Search),
    BottomItem("settings", "Mehr", Icons.Filled.Tune, Icons.Outlined.Tune)
)

fun NavHostController.openCard(card: TcgCard) {
    navigate("card/${Uri.encode(card.id)}")
}

@Composable
fun CardLensNavHost() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = bottomItems.any { it.route == currentRoute }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    bottomItems.forEach { item ->
                        NavigationBarItem(
                            selected = currentRoute == item.route,
                            onClick = {
                                navController.navigate(item.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = if (currentRoute == item.route) item.selectedIcon else item.icon,
                                    contentDescription = item.label
                                )
                            },
                            label = { Text(item.label) },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "collection",
            modifier = Modifier.padding(innerPadding)
        ) {
            composable("collection") {
                CollectionScreen(onOpenCard = { navController.openCard(it) })
            }
            composable("decks") {
                DecksScreen(onOpenDeck = { navController.navigate("deck/$it") })
            }
            composable("scan") {
                ScannerScreen(onOpenCard = { navController.openCard(it) })
            }
            composable("search") {
                SearchScreen(onOpenCard = { navController.openCard(it) })
            }
            composable("settings") {
                SettingsScreen(onOpenTrade = { navController.navigate("trade") })
            }
            composable("trade") {
                TradeScreen(
                    onBack = { navController.popBackStack() },
                    onOpenCard = { navController.openCard(it) }
                )
            }
            composable(
                route = "deck/{deckId}",
                arguments = listOf(navArgument("deckId") { type = NavType.LongType })
            ) { entry ->
                val deckId = entry.arguments?.getLong("deckId") ?: 0L
                DeckDetailScreen(
                    deckId = deckId,
                    onBack = { navController.popBackStack() },
                    onOpenCard = { navController.openCard(it) }
                )
            }
            composable("card/{cardId}") { entry ->
                val cardId = Uri.decode(entry.arguments?.getString("cardId").orEmpty())
                CardDetailScreen(cardId = cardId, onBack = { navController.popBackStack() })
            }
        }
    }
}
