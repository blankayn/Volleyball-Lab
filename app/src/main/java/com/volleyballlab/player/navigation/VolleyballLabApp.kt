package com.volleyballlab.player.navigation

import android.view.Window
import androidx.core.view.WindowCompat
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import com.volleyballlab.player.R
import androidx.navigation.toRoute
import com.volleyballlab.player.domain.model.UserRole
import com.volleyballlab.player.feature.assessment.AssessmentScreen
import com.volleyballlab.player.feature.auth.LoginScreen
import com.volleyballlab.player.feature.auth.SignUpScreen
import com.volleyballlab.player.feature.auth.findActivity
import com.volleyballlab.player.feature.coach.CoachScreen
import com.volleyballlab.player.feature.coach.PlayerAssignmentScreen
import com.volleyballlab.player.feature.coachmode.coachWorkspaceBackground
import com.volleyballlab.player.feature.coachmode.CoachAccountScreen
import com.volleyballlab.player.feature.coachmode.CoachAssignEditScreen
import com.volleyballlab.player.feature.coachmode.CoachAssignListScreen
import com.volleyballlab.player.feature.coachmode.CoachDashboardScreen
import com.volleyballlab.player.feature.coachmode.CoachPlayerScreen
import com.volleyballlab.player.feature.coachmode.CoachReviewScreen
import com.volleyballlab.player.feature.coachmode.CoachRosterScreen
import com.volleyballlab.player.feature.home.HomeScreen
import com.volleyballlab.player.feature.session.SessionSummaryScreen
import com.volleyballlab.player.feature.profile.ProfileScreen
import com.volleyballlab.player.feature.progress.ProgressScreen
import com.volleyballlab.player.feature.splash.SplashScreen
import com.volleyballlab.player.feature.splash.StartDestination
import com.volleyballlab.player.feature.splash.StartupViewModel
import com.volleyballlab.player.feature.train.capture.CaptureScreen
import com.volleyballlab.player.feature.train.drill.DrillSelectScreen
import com.volleyballlab.player.feature.train.result.SessionResultScreen
import com.volleyballlab.player.feature.train.skill.SkillSelectScreen
import com.volleyballlab.player.feature.train.drill.DrillLibraryScreen
import com.volleyballlab.player.feature.train.drill.DrillDetailScreen
import com.volleyballlab.player.feature.train.drill.DrillPracticeScreen
import com.volleyballlab.player.feature.train.drill.DrillPracticeSummaryScreen
import com.volleyballlab.player.feature.welcome.WelcomeScreen
import com.volleyballlab.player.ui.component.VolleyballBackdrop
import com.volleyballlab.player.ui.component.LocalScreenEntranceEnabled
import com.volleyballlab.player.ui.theme.LocalReduceMotion
import com.volleyballlab.player.ui.theme.VbMotion
import com.volleyballlab.player.ui.theme.vbTween

/**
 * Root composable: splash, then the navigation shell.
 */
@Composable
fun VolleyballLabApp() {
    var firstBrandedFrameDrawn by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // Firebase and repository construction can block the first composition on
        // a cold process. Let the lightweight splash reach the screen first.
        withFrameNanos { }
        withFrameNanos { }
        firstBrandedFrameDrawn = true
    }
    if (!firstBrandedFrameDrawn) {
        SplashScreen(isReady = false, onFinished = {})
        return
    }

    val startupViewModel: StartupViewModel = viewModel(factory = StartupViewModel.Factory)
    val isReady by startupViewModel.isReady.collectAsStateWithLifecycle()
    val startDestination by startupViewModel.startDestination.collectAsStateWithLifecycle()
    val hasOnboarded by startupViewModel.hasOnboarded.collectAsStateWithLifecycle()
    val role by startupViewModel.role.collectAsStateWithLifecycle()

    // Not rememberSaveable: after a rotation the splash should not replay. The
    // startup work has already run and making the user watch the intro again
    // would be a regression, not a feature.
    var splashComplete by remember { mutableStateOf(false) }

    Crossfade(
        targetState = splashComplete,
        // Long enough to hide the seam between the splash's opaque background and
        // the app's surface. The splash outro deliberately stays opaque to its
        // final frame precisely so this crossfade has something solid to work
        // against - see section 3 of docs/rive/splash-contract.md.
        animationSpec = vbTween(durationMillis = VbMotion.Long),
        label = "splashHandoff",
    ) { complete ->
        if (complete) {
            // Non-null by here: the splash only reports complete after isReady, and
            // isReady is set in the same breath as the destination. Login is the
            // safe read of "somehow neither", for the reason StartupViewModel gives.
            val start = startDestination ?: StartDestination.Login

            // Two shells, chosen by role, sharing one login form.
            //
            // The choice is made here rather than inside a single NavHost because a
            // role change has to rebuild the graph, not navigate within it. Swapping
            // which composable is called does exactly that: the coach shell's
            // NavHost and NavController are torn down with it, so a coach who
            // switches to the player app cannot press back into a review queue that
            // no longer belongs to them.
            //
            // There is no guard on `start` here: with no session the role flow
            // reports Player, so a signed-out launch lands in the player shell at
            // the login form, and the swap happens the instant a coach's session
            // restores or is created.
            if (role == UserRole.Coach) {
                CoachShell()
            } else {
                // Resolved once per shell instance, not on every recomposition.
                //
                // `start` is the answer startup gave, and it is right on every path
                // but one: this branch is also entered when a coach switches back to
                // an athlete account mid-run, and startup's answer was computed when
                // that account was not signed in. Deferring to `hasSession` - a
                // direct read, not a flow that could still be catching up - is what
                // stops that switch landing on the login form.
                //
                // `remember` rather than plain computation because this is a
                // NavHost start destination: recomputing it under a live graph would
                // rebuild the graph and reset the back stack, which is precisely the
                // bug this is fixing, in the other direction.
                val resolvedStart = remember {
                    if (!startupViewModel.hasSession) {
                        // A coach sign-out rebuilds this shell, but startup may
                        // still say CoachHome. Never reuse it without a session.
                        if (start == StartDestination.Welcome) {
                            StartDestination.Welcome
                        } else {
                            StartDestination.Login
                        }
                    } else if (start != StartDestination.Login && start != StartDestination.CoachHome &&
                        start != StartDestination.Welcome) {
                        start
                    } else if (startupViewModel.hasOnboarded.value) {
                        StartDestination.Home
                    } else {
                        StartDestination.Assessment
                    }
                }
                MainShell(
                    start = resolvedStart,
                    hasOnboarded = { hasOnboarded },
                )
            }
        } else {
            SplashScreen(
                isReady = isReady,
                onFinished = { splashComplete = true },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun MainShell(start: StartDestination, hasOnboarded: () -> Boolean) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val window = LocalContext.current.findActivity()?.window
    SideEffect {
        val isCapture = currentDestination?.hasRoute(CaptureRoute::class) == true
        setSystemBarIconAppearance(window, useDarkIcons = !isCapture)
    }

    // Three kinds of screen have no business showing tabs. The camera is full-bleed:
    // a bottom bar over a live viewfinder covers the athlete's feet, which the
    // framing gate needs to see, and offers a tab switch that would tear down the
    // camera mid-recording. Login and onboarding are before the app proper - there
    // is nothing yet to switch to, and offering the switch would imply there is.
    val hiddenBarRoutes = listOf(
        CaptureRoute::class,
        WelcomeRoute::class,
        LoginRoute::class,
        SignUpRoute::class,
        AssessmentRoute::class,
        CoachDirectoryRoute::class,
        CoachProfileRoute::class,
        MyCoachRoute::class,
        MyCoachSectionRoute::class,
    )
    val showBottomBar = hiddenBarRoutes.none { currentDestination?.hasRoute(it) == true }

    Scaffold(
        topBar = {
            if (showBottomBar) {
                androidx.compose.foundation.layout.Column {
                    TopAppBar(
                        title = { AppBrandTitle() },
                        actions = { com.volleyballlab.player.feature.notifications.NotificationBell() },
                    )
                    if (currentDestination?.hasRoute<CoachRoute>() == true) {
                        com.volleyballlab.player.feature.coach.CoachSyncBanner(coach = false)
                    }
                }
            }
        },
        bottomBar = {
            if (showBottomBar) {
                VbBottomBar(
                    navController = navController,
                    currentDestination = currentDestination,
                    selectedTab = when {
                        currentDestination?.hasRoute<SessionSummaryRoute>() == true ->
                            if (backStackEntry?.toRoute<SessionSummaryRoute>()?.fromProgress == true)
                                TopLevelDestination.Progress else TopLevelDestination.Home
                        currentDestination?.hasRoute<SessionResultRoute>() == true &&
                            backStackEntry?.toRoute<SessionResultRoute>()?.returnToSummary == true ->
                            if (backStackEntry?.toRoute<SessionResultRoute>()?.summaryFromProgress == true)
                                TopLevelDestination.Progress else TopLevelDestination.Home
                        currentDestination?.hasRoute<PlayerAssignmentRoute>() == true ||
                            (currentDestination?.hasRoute<SessionResultRoute>() == true &&
                                backStackEntry?.toRoute<SessionResultRoute>()?.assignmentId != null) -> TopLevelDestination.Coach
                        else -> null
                    },
                )
            }
        },
    ) { innerPadding ->
        // The route owns its entrance. Mount card content immediately instead of
        // following the page fade with another sequence of delayed entrances.
        CompositionLocalProvider(LocalScreenEntranceEnabled provides false) {
            VbNavHost(
                navController = navController,
                start = start,
                hasOnboarded = hasOnboarded,
                contentPadding = innerPadding,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * The coach half of the app.
 *
 * A sibling of [MainShell], not a mode inside it. The two have different tabs,
 * different graphs and different back stacks, and the only thing they share is the
 * auth screens - which is why those appear in both.
 *
 * The coach shell has no start-destination parameter. It is only ever built for a
 * signed-in coach, so there is exactly one place it can open: the dashboard.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun CoachShell() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val coachGraph = (LocalContext.current.applicationContext as com.volleyballlab.player.VolleyballLabApplication).container
    val coachUser by coachGraph.authRepository.currentUser.collectAsStateWithLifecycle(null)
    DisposableEffect(coachUser?.uid) {
        coachUser?.uid?.let(coachGraph.coachPresenceReporter::connect)
        onDispose { coachGraph.coachPresenceReporter.disconnect() }
    }
    LaunchedEffect(currentDestination, coachUser?.uid) {
        coachGraph.coachPresenceReporter.reviewing(currentDestination?.hasRoute<CoachReviewRoute>() == true || currentDestination?.hasRoute<CoachAssignEditRoute>() == true)
    }
    var selectedTab by rememberSaveable { mutableStateOf(CoachTabDestination.Dashboard) }
    val topLevelTab = CoachTabDestination.All.firstOrNull { tab ->
        currentDestination?.hierarchy?.any { it.hasRoute(tab.graphClass) } == true
    }
    LaunchedEffect(topLevelTab) { if (topLevelTab != null) selectedTab = topLevelTab }
    val window = LocalContext.current.findActivity()?.window
    SideEffect { setSystemBarIconAppearance(window, useDarkIcons = true) }

    // The same rule as the athlete's shell, minus the camera: auth screens have
    // nothing to switch to. Detail screens keep their tabs, because a coach three
    // levels into a player's history should still be able to jump to their queue.
    val hiddenBarRoutes = listOf(LoginRoute::class, SignUpRoute::class, CoachQuestionsRoute::class)
    val showBottomBar = hiddenBarRoutes.none { currentDestination?.hasRoute(it) == true }

    Scaffold(
        topBar = {
            if (showBottomBar) {
                androidx.compose.foundation.layout.Column {
                    TopAppBar(
                        title = { AppBrandTitle() },
                        actions = { Box(Modifier.padding(end = 10.dp)) { com.volleyballlab.player.feature.notifications.NotificationBell() } },
                        colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                    )
                    com.volleyballlab.player.feature.coach.CoachSyncBanner(coach = true)
                }
            }
        },
        bottomBar = {
            if (showBottomBar) {
                CoachBottomBar(
                    navController = navController,
                    selectedTab = topLevelTab ?: selectedTab,
                )
            }
        },
    ) { innerPadding ->
        // Keep the neutral workspace surface behind every coach destination.
        Box(Modifier.fillMaxSize().coachWorkspaceBackground()) {
            CompositionLocalProvider(LocalScreenEntranceEnabled provides false) {
                CoachNavHost(
                    navController = navController,
                    contentPadding = innerPadding,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

private fun setSystemBarIconAppearance(window: Window?, useDarkIcons: Boolean) {
    window ?: return
    WindowCompat.getInsetsController(window, window.decorView).apply {
        isAppearanceLightStatusBars = useDarkIcons
        isAppearanceLightNavigationBars = useDarkIcons
    }
}

@Composable
private fun AppBrandTitle() {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Image(
            painter = painterResource(R.drawable.volleyball_lab_logo),
            contentDescription = null,
            modifier = Modifier.size(28.dp),
        )
        Text("VOLLEYBALL LAB", style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

/**
 * The coach shell's tab bar.
 *
 * Differs from the athlete's in one way: the selected tab is marked by a short
 * rust rule along the *top* edge rather than by Material's pill behind the icon.
 * On a white bar over a pale page the pill reads as a fifth surface competing
 * with the cards above it, whereas a rule on the bar's own edge sits outside the
 * content entirely. The icon and label still take the primary colour, so the
 * selection is never carried by the rule alone.
 */
@Composable
private fun CoachBottomBar(
    navController: NavHostController,
    selectedTab: CoachTabDestination,
) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
        CoachTabDestination.All.forEach { destination ->
            val selected = selectedTab == destination

            NavigationBarItem(
                selected = selected,
                onClick = { navController.navigateToCoachTab(destination) },
                icon = {
                    Box(contentAlignment = Alignment.TopCenter) {
                        Icon(
                            imageVector = destination.icon,
                            contentDescription = null,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                        if (selected) {
                            Box(
                                Modifier
                                    .width(28.dp)
                                    .height(3.dp)
                                    .clip(RoundedCornerShape(bottomStart = 3.dp, bottomEnd = 3.dp))
                                    .background(MaterialTheme.colorScheme.primary),
                            )
                        }
                    }
                },
                label = { Text(stringResource(destination.titleRes)) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    // Transparent, because the rule above is the indicator. Left at
                    // the default the two would both be drawn and read as a bug.
                    indicatorColor = Color.Transparent,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}

/** Identical tab semantics to the athlete's shell; see [navigateToTopLevel]. */
internal fun NavHostController.navigateToCoachTab(destination: CoachTabDestination) {
    if (currentDestination?.hierarchy?.any { it.hasRoute(destination.graphClass) } == true) return
    navigate(destination.route) {
        popUpTo<CoachDashboardRoute> { saveState = true }
        launchSingleTop = true
        restoreState = destination != CoachTabDestination.Dashboard
    }
}

/** Section navigation keeps the combined workspace as its single Back destination. */
internal fun NavHostController.openMyCoachSection(section: String) {
    navigate(MyCoachSectionRoute(section)) {
        popUpTo<MyCoachRoute> { inclusive = false }
        launchSingleTop = true
    }
}

@Composable
private fun CoachNavHost(
    navController: NavHostController,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = LocalReduceMotion.current
    val page = VbMotion.tween<Float>(VbMotion.Navigation, reduceMotion = reduceMotion)
    val quick = VbMotion.tween<Float>(VbMotion.Short, reduceMotion = reduceMotion)

    NavHost(
        navController = navController,
        startDestination = CoachDashboardRoute,
        modifier = modifier,
        enterTransition = { fadeIn(page) },
        exitTransition = { fadeOut(quick) },
        popEnterTransition = { fadeIn(quick) },
        popExitTransition = { fadeOut(quick) },
    ) {
        composable<CoachDashboardRoute> {
            CoachDashboardScreen(
                onOpenReview = { playerUid, sessionId ->
                    navController.navigate(CoachReviewRoute(playerUid, sessionId))
                },
                // The dashboard's shortcuts switch tabs rather than pushing, so
                // they go through the same helper the bottom bar uses and inherit
                // its saved-state behaviour. A push would leave the coach with a
                // back stack the tab bar knows nothing about.
                onOpenRoster = { navController.navigateToCoachTab(CoachTabDestination.Roster) },
                onOpenAssign = { navController.navigateToCoachTab(CoachTabDestination.Assign) },
                onOpenAccount = { navController.navigateToCoachTab(CoachTabDestination.Account) },
                onOpenQuestions = { navController.navigate(CoachQuestionsRoute) },
                contentPadding = contentPadding,
            )
        }

        composable<CoachRosterRoute> {
            CoachRosterScreen(
                onOpenPlayer = { navController.navigate(CoachPlayerRoute(it)) },
                contentPadding = contentPadding,
            )
        }

        composable<CoachQuestionsRoute> {
            com.volleyballlab.player.feature.coach.CoachQuestionsScreen({ navController.popBackStack() }, contentPadding)
        }

        composable<CoachPlayerRoute> { entry ->
            val route = entry.toRoute<CoachPlayerRoute>()
            CoachPlayerScreen(
                playerUid = route.playerUid,
                onBack = { navController.popBackStack() },
                onOpenReview = { playerUid, sessionId ->
                    navController.navigate(CoachReviewRoute(playerUid, sessionId))
                },
                // Opens the form with this athlete already chosen. A coach who got
                // here by reading one player's history has already answered the
                // "who is this for" question the form would otherwise ask.
                onAssignDrill = { navController.navigate(CoachAssignEditRoute(playerUid = it)) },
                contentPadding = contentPadding,
            )
        }

        composable<CoachReviewRoute> { entry ->
            val route = entry.toRoute<CoachReviewRoute>()
            CoachReviewScreen(
                playerUid = route.playerUid,
                sessionId = route.sessionId,
                onBack = { navController.popBackStack() },
                contentPadding = contentPadding,
            )
        }

        navigation<CoachAssignGraph>(startDestination = CoachAssignListRoute) {
            composable<CoachAssignListRoute> {
                CoachAssignListScreen(
                    onNew = { navController.navigate(CoachAssignEditRoute()) },
                    onEdit = { navController.navigate(CoachAssignEditRoute(assignmentId = it)) },
                    contentPadding = contentPadding,
                )
            }
        }

        // Outside the Assign graph on purpose: the form is reachable from a
        // player's profile as well as from the Assign tab, and nesting it would
        // make backing out of it jump the coach into a tab they never opened.
        composable<CoachAssignEditRoute> { entry ->
            val route = entry.toRoute<CoachAssignEditRoute>()
            CoachAssignEditScreen(
                presetPlayerUid = route.playerUid,
                assignmentId = route.assignmentId,
                onBack = { navController.popBackStack() },
                contentPadding = contentPadding,
            )
        }

        composable<EditAccountRoute> {
            com.volleyballlab.player.feature.profile.EditAccountScreen({ navController.popBackStack() }, contentPadding)
        }
        composable<CoachAccountRoute> {
            CoachAccountScreen(
                // Signing out rebuilds the shell at the login screen.
                onSignedOut = {},
                contentPadding = contentPadding,
            )
        }
    }
}

@Composable
private fun VbBottomBar(
    navController: NavHostController,
    currentDestination: NavDestination?,
    selectedTab: TopLevelDestination? = null,
) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        TopLevelDestination.All.forEach { destination ->
            // Matches against the whole hierarchy, not just the leaf, so the Train
            // tab stays highlighted while the user is three screens deep inside it.
            val selected = if (selectedTab != null) destination == selectedTab else currentDestination?.hierarchy
                ?.any { it.hasRoute(destination.graphClass) } == true

            NavigationBarItem(
                selected = selected,
                onClick = { navController.navigateToTopLevel(destination) },
                icon = {
                    Icon(
                        imageVector = destination.icon,
                        contentDescription = null,
                    )
                },
                label = { Text(stringResource(destination.titleRes)) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}

/**
 * Home is the permanent tab anchor, including after login or assessment.
 * Navigation may associate a popped tab's saved stack with that anchor too.
 * Restoring Home would then reopen Train instead of displaying the dashboard.
 * Restore only the other tabs; their drill screens and scroll state stay saved.
 */
internal fun NavHostController.navigateToTopLevel(destination: TopLevelDestination) {
    val entry = currentBackStackEntry
    if (destination == TopLevelDestination.Train && entry?.destination?.hasRoute<SessionResultRoute>() == true) {
        val feedback = entry.toRoute<SessionResultRoute>()
        if (feedback.returnToSummary) {
            // History feedback shares the result screen, but selecting Train
            // should still open the training tab rather than stay in history.
            popBackStack(SessionSummaryRoute(feedback.sessionId, feedback.summaryFromProgress), inclusive = false)
        }
    }
    if (currentDestination?.hierarchy?.any { it.hasRoute(destination.graphClass) } == true) return
    navigate(destination.route) {
        popUpTo<HomeRoute> { saveState = true }
        launchSingleTop = true
        restoreState = destination != TopLevelDestination.Home
    }
}

/**
 * Leaves the auth screens for the app proper.
 *
 * The whole auth stack is popped rather than merely navigated away from. Without
 * `popUpTo`, the system back gesture from the dashboard would return a signed-in
 * athlete to a login form, which reads as having been signed out.
 */
private fun NavHostController.leaveAuth(onboarded: Boolean) {
    navigate(if (onboarded) HomeRoute else AssessmentRoute) {
        popUpTo<LoginRoute> { inclusive = true }
        launchSingleTop = true
    }
}

@Composable
private fun VbNavHost(
    navController: NavHostController,
    start: StartDestination,
    hasOnboarded: () -> Boolean,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    // NavHost's transition parameters are plain lambdas, not composable ones, so
    // the reduce-motion preference is read here and captured.
    val reduceMotion = LocalReduceMotion.current
    val page = VbMotion.tween<Float>(VbMotion.Navigation, reduceMotion = reduceMotion)
    val quick = VbMotion.tween<Float>(VbMotion.Short, reduceMotion = reduceMotion)

    NavHost(
        navController = navController,
        startDestination = when (start) {
            StartDestination.Welcome -> WelcomeRoute
            StartDestination.Login -> LoginRoute
            StartDestination.Assessment -> AssessmentRoute
            StartDestination.Home -> HomeRoute
            // Unreachable in practice: a CoachHome start builds `CoachShell`, not
            // this graph. Spelled out rather than folded into an `else` so that
            // adding a sixth destination fails here loudly instead of silently
            // dropping someone on the dashboard.
            StartDestination.CoachHome -> HomeRoute
        },
        modifier = modifier,
        // A single short fade-through. Scale and the staggered entrances that
        // followed it made a tab switch feel like three animations competing.
        enterTransition = { fadeIn(page) },
        exitTransition = { fadeOut(quick) },
        popEnterTransition = { fadeIn(quick) },
        popExitTransition = { fadeOut(quick) },
    ) {
        composable<WelcomeRoute> {
            WelcomeScreen(
                onGetStarted = {
                    navController.navigate(SignUpRoute) {
                        popUpTo<WelcomeRoute> { inclusive = true }
                    }
                },
                onLogIn = {
                    navController.navigate(LoginRoute) {
                        popUpTo<WelcomeRoute> { inclusive = true }
                    }
                },
            )
        }

        composable<LoginRoute> {
            LoginScreen(
                // Nothing to do for a coach: the role flow is about to swap this
                // whole shell for the coach one. Navigating first would show a
                // frame of the athlete's dashboard on the way past.
                onAuthenticated = { role ->
                    if (role == UserRole.Player) navController.leaveAuth(hasOnboarded())
                },
                onSignUp = { navController.navigate(SignUpRoute) },
            )
        }

        composable<SignUpRoute> {
            SignUpScreen(
                // A brand-new account has by definition not been onboarded, so it
                // goes to the questionnaire rather than consulting the flag - which
                // could still be true from whoever used this phone last.
                onAuthenticated = { role ->
                    if (role == UserRole.Player) navController.leaveAuth(onboarded = false)
                },
                onBackToLogin = { navController.popBackStack() },
            )
        }

        composable<AssessmentRoute> {
            AssessmentScreen(
                onFinished = {
                    navController.navigate(HomeRoute) {
                        popUpTo<AssessmentRoute> { inclusive = true }
                    }
                },
                contentPadding = contentPadding,
            )
        }

        composable<HomeRoute> {
            HomeScreen(
                onStartTraining = { navController.navigateToTopLevel(TopLevelDestination.Train) },
                onSkillSelected = { skill -> navController.navigate(DrillSelectRoute(skill)) },
                onTakeAssessment = { navController.navigate(AssessmentRoute) },
                onViewAllSessions = {
                    navController.navigateToTopLevel(TopLevelDestination.Progress)
                },
                onViewAssignments = { navController.navigateToTopLevel(TopLevelDestination.Coach) },
                onOpenSession = { navController.navigate(SessionSummaryRoute(it)) },
                contentPadding = contentPadding,
            )
        }

        composable<SessionSummaryRoute> { entry ->
            val route = entry.toRoute<SessionSummaryRoute>()
            SessionSummaryScreen(
                sessionId = route.sessionId,
                onBack = { navController.popBackStack() },
                onOpenFeedback = { navController.navigate(SessionResultRoute(route.sessionId,
                    returnToSummary = true, summaryFromProgress = route.fromProgress)) },
                contentPadding = contentPadding,
            )
        }

        navigation<TrainGraph>(startDestination = SkillSelectRoute) {
            composable<SkillSelectRoute> {
                SkillSelectScreen(
                    onOpenDrillLibrary = { navController.navigate(DrillLibraryRoute) },
                    onSkillSelected = { skill ->
                        navController.navigate(DrillSelectRoute(skill))
                    },
                    contentPadding = contentPadding,
                )
            }

            composable<DrillSelectRoute> { entry ->
                val route = entry.toRoute<DrillSelectRoute>()
                DrillSelectScreen(
                    skill = route.skill,
                    onDrillSelected = { drill ->
                        navController.navigate(CaptureRoute(drill.skill, drill.id))
                    },
                    onBack = { navController.popBackStack() },
                    contentPadding = contentPadding,
                )
            }

            composable<DrillLibraryRoute> {
                DrillLibraryScreen(
                    onDrill = { navController.navigate(DrillDetailRoute(it)) },
                    onResume = { draft -> navController.navigate(DrillPracticeRoute(draft.drillId, draft.config.sets, draft.config.reps, draft.config.restSeconds, draft.id)) },
                    onSummary = { navController.navigate(DrillPracticeSummaryRoute(it)) },
                    onBack = { navController.popBackStack() },
                    contentPadding = contentPadding,
                )
            }
            composable<DrillDetailRoute> { entry ->
                val route = entry.toRoute<DrillDetailRoute>()
                DrillDetailScreen(route.drillId,
                    onPractice = { config -> navController.navigate(DrillPracticeRoute(route.drillId, config.sets, config.reps, config.restSeconds)) },
                    onResume = { draft -> navController.navigate(DrillPracticeRoute(draft.drillId, draft.config.sets, draft.config.reps, draft.config.restSeconds, draft.id)) },
                    onCamera = { drill -> navController.navigate(CaptureRoute(drill.skill, drill.id, route.drillId)) },
                    onBack = { navController.popBackStack() }, contentPadding = contentPadding,
                )
            }
            composable<DrillPracticeRoute> { entry ->
                val route = entry.toRoute<DrillPracticeRoute>()
                DrillPracticeScreen(route.drillId, route.sets, route.reps, route.restSeconds, route.resumeId,
                    onSummary = { id -> navController.navigate(DrillPracticeSummaryRoute(id)) { popUpTo<DrillPracticeRoute> { inclusive = true } } },
                    onBack = { navController.popBackStack() }, contentPadding = contentPadding,
                )
            }
            composable<DrillPracticeSummaryRoute> { entry ->
                val route = entry.toRoute<DrillPracticeSummaryRoute>()
                DrillPracticeSummaryScreen(route.workoutId, onBack = { navController.popBackStack() }, contentPadding = contentPadding)
            }
            composable<CaptureRoute> { entry ->
                val route = entry.toRoute<CaptureRoute>()
                CaptureScreen(
                    skill = route.skill,
                    drillId = route.drillId,
                    assignmentId = route.assignmentId,
                    onSessionComplete = { sessionId ->
                        navController.navigate(SessionResultRoute(sessionId, route.returnToDrillId, route.assignmentId)) {
                            // Drops the camera from the back stack. Backing out of
                            // results should not resume a finished recording.
                            popUpTo<CaptureRoute> { inclusive = true }
                        }
                    },
                    onBack = { navController.popBackStack() },
                )
            }

            composable<SessionResultRoute> { entry ->
                val route = entry.toRoute<SessionResultRoute>()
                SessionResultScreen(
                    sessionId = route.sessionId,
                    assignmentId = route.assignmentId,
                    onDone = {
                        if (route.returnToSummary) {
                            navController.popBackStack(SessionSummaryRoute(route.sessionId, route.summaryFromProgress), inclusive = false)
                        } else if (route.assignmentId != null) {
                            navController.popBackStack(PlayerAssignmentRoute(route.assignmentId), inclusive = false)
                        } else if (route.returnToDrillId != null) navController.navigate(DrillDetailRoute(route.returnToDrillId)) {
                            popUpTo<DrillDetailRoute> { inclusive = true }; launchSingleTop = true
                        } else navController.navigate(SkillSelectRoute) { popUpTo<SkillSelectRoute> { inclusive = true } }
                    },
                    doneLabel = if (route.returnToSummary) stringResource(R.string.session_summary_back_from_feedback) else null,
                    contentPadding = contentPadding,
                )
            }
        }

        composable<ProgressRoute> {
            ProgressScreen(
                onStartTraining = { navController.navigateToTopLevel(TopLevelDestination.Train) },
                onOpenSession = { navController.navigate(SessionSummaryRoute(it, fromProgress = true)) },
                contentPadding = contentPadding,
            )
        }

        composable<CoachRoute> {
            CoachScreen(
                // Straight to the drills for the skill the advice is about, not
                // to the skill picker: the athlete has just been told which skill
                // to work on, and asking them to choose it again is asking them
                // to re-answer a question the screen already answered.
                onPractiseSkill = { skill -> navController.navigate(DrillSelectRoute(skill)) },
                onStartTraining = { navController.navigateToTopLevel(TopLevelDestination.Train) },
                onOpenAssignment = { navController.navigate(PlayerAssignmentRoute(it)) },
                onBrowseCoaches = { navController.navigate(CoachDirectoryRoute) },
                onMyCoach = { navController.navigate(MyCoachRoute) },
                contentPadding = contentPadding,
            )
        }

        composable<PlayerAssignmentRoute> { entry ->
            val route = entry.toRoute<PlayerAssignmentRoute>()
            PlayerAssignmentScreen(
                assignmentId = route.assignmentId,
                onBack = { navController.popBackStack() },
                onStart = { skill, drillId ->
                    navController.navigate(CaptureRoute(skill, drillId, assignmentId = route.assignmentId))
                },
                onOpenResult = { sessionId ->
                    navController.navigate(SessionResultRoute(sessionId, assignmentId = route.assignmentId))
                },
                contentPadding = contentPadding,
            )
        }

        composable<CoachDirectoryRoute> {
            com.volleyballlab.player.feature.coach.CoachDirectoryScreen(
                onBack = { navController.popBackStack() },
                onOpenCoach = { navController.navigate(CoachProfileRoute(it)) },
                contentPadding = contentPadding,
            )
        }
        composable<MyCoachRoute> {
            com.volleyballlab.player.feature.coach.MyCoachScreen(
                onBack = { navController.popBackStack() },
                onBrowse = { navController.navigate(CoachDirectoryRoute) },
                onOpenAssignment = { navController.navigate(PlayerAssignmentRoute(it)) },
                contentPadding = contentPadding,
                onOpenProfile = { navController.navigate(CoachProfileRoute(it)) },
                onOpenSection = navController::openMyCoachSection,
            )
        }
        composable<MyCoachSectionRoute> { entry ->
            val workspace = remember(entry) { navController.getBackStackEntry<MyCoachRoute>() }
            // Share loaded coach data and pending form actions with the hub.
            CompositionLocalProvider(androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner provides workspace) {
                com.volleyballlab.player.feature.coach.MyCoachScreen(
                    onBack = { navController.popBackStack() },
                    onBrowse = { navController.navigate(CoachDirectoryRoute) },
                    onOpenAssignment = { navController.navigate(PlayerAssignmentRoute(it)) },
                    contentPadding = contentPadding,
                    onOpenProfile = { navController.navigate(CoachProfileRoute(it)) },
                    section = entry.toRoute<MyCoachSectionRoute>().section,
                    onOpenSection = navController::openMyCoachSection,
                )
            }
        }
        composable<CoachProfileRoute> { entry ->
            com.volleyballlab.player.feature.coach.CoachProfileScreen(
                coachUid = entry.toRoute<CoachProfileRoute>().coachUid,
                onBack = { navController.popBackStack() },
                onRequestSent = {
                    navController.navigate(CoachRoute) {
                        popUpTo<CoachDirectoryRoute> { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onOpenWorkspace = {
                    if (!navController.popBackStack<MyCoachRoute>(inclusive = false)) navController.navigate(MyCoachRoute)
                },
                contentPadding = contentPadding,
            )
        }

        composable<EditAccountRoute> {
            com.volleyballlab.player.feature.profile.EditAccountScreen({ navController.popBackStack() }, contentPadding)
        }
        composable<ProfileRoute> {
            ProfileScreen(
                onBrowseCoaches = { navController.navigate(CoachDirectoryRoute) },
                // Signing out returns to login and takes the whole tab stack with
                // it, so back from the login form cannot re-enter a signed-out app.
                onSignedOut = {
                    navController.navigate(LoginRoute) {
                        popUpTo(navController.graph.id) { inclusive = true }
                    }
                },
                contentPadding = contentPadding,
            )
        }
    }
}
