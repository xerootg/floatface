package app.floatface.wear.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.foundation.ExperimentalWearFoundationApi
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material.HorizontalPageIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PageIndicatorState
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import app.floatface.core.BoardConfig
import app.floatface.core.UiState
import app.floatface.core.UserIntent
import kotlinx.coroutines.flow.distinctUntilChanged
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val PAGE_COUNT = 4

/**
 * Root screen (SPEC §8.1/§8.2): a 4-page rotary/swipe pager (Ride, Stats, Board,
 * Diagnostics) with a status/clock header and a bottom page indicator. Pure
 * function of [state]; every user action flows out through [onIntent].
 */
@OptIn(ExperimentalWearFoundationApi::class)
@Composable
fun FloatfaceApp(
    state: UiState,
    onIntent: (UserIntent) -> Unit,
    config: BoardConfig = BoardConfig.EMPTY,
    onEditUnlockBytes: () -> Unit = {},
    onEditBleMac: () -> Unit = {},
    onClearConfig: () -> Unit = {},
) {
    var showConfig by remember { mutableStateOf(false) }
    if (showConfig) {
        ConfigScreen(
            config = config,
            onEditUnlockBytes = onEditUnlockBytes,
            onEditBleMac = onEditBleMac,
            onClearConfig = onClearConfig,
            onBack = { showConfig = false },
        )
        return
    }

    val pagerState = rememberPagerState(initialPage = state.page) { PAGE_COUNT }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .collect { page -> onIntent(UserIntent.SelectPage(page)) }
    }

    LaunchedEffect(state.page) {
        if (pagerState.currentPage != state.page && state.page in 0 until PAGE_COUNT) {
            pagerState.animateScrollToPage(state.page)
        }
    }

    LaunchedEffect(focusRequester) {
        focusRequester.requestFocus()
    }

    val pageIndicatorState = remember(pagerState) {
        object : PageIndicatorState {
            override val pageOffset: Float get() = pagerState.currentPageOffsetFraction
            override val selectedPage: Int get() = pagerState.currentPage
            override val pageCount: Int get() = PAGE_COUNT
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        timeText = { StatusHeader(state) },
        pageIndicator = { HorizontalPageIndicator(pageIndicatorState = pageIndicatorState) },
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .focusable()
                .rotaryScrollable(
                    behavior = RotaryScrollableDefaults.behavior(scrollableState = pagerState),
                    focusRequester = focusRequester,
                ),
        ) { page ->
            when (page) {
                0 -> RideScreen(state, onIntent)
                1 -> StatsScreen(state, onIntent)
                2 -> BoardScreen(state, onIntent)
                else -> DiagnosticsScreen(state, onIntent, onOpenConfig = { showConfig = true })
            }
        }
    }
}

@Composable
private fun StatusHeader(state: UiState) {
    val clock = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = connectionStatusText(state.connection), fontSize = 10.sp, color = MaterialTheme.colors.onBackground)
            Text(text = clock.format(Date()), fontSize = 10.sp, color = MaterialTheme.colors.onBackground)
        }
    }
}
