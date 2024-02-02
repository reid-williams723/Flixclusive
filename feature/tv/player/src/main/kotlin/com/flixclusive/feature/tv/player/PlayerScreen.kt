package com.flixclusive.feature.tv.player

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import com.flixclusive.core.ui.player.PLAYER_CONTROL_VISIBILITY_TIMEOUT
import com.flixclusive.core.ui.player.PlayerScreenNavArgs
import com.flixclusive.core.ui.player.util.PlayerUiUtil.AudioFocusManager
import com.flixclusive.core.ui.player.util.PlayerUiUtil.LifecycleAwarePlayer
import com.flixclusive.core.ui.player.util.PlayerUiUtil.LocalPlayerManager
import com.flixclusive.core.ui.player.util.PlayerUiUtil.ObserveNewLinksAndSubtitles
import com.flixclusive.core.ui.player.util.PlayerUiUtil.formatPlayerTitle
import com.flixclusive.core.ui.tv.component.SourceDataDialog
import com.flixclusive.core.ui.tv.util.handleDPadKeyEvents
import com.flixclusive.core.ui.tv.util.provideLocalDirectionalFocusRequester
import com.flixclusive.core.util.android.getActivity
import com.flixclusive.core.util.film.FilmType
import com.flixclusive.feature.tv.player.controls.PlaybackControls
import com.flixclusive.model.provider.SourceDataState
import com.flixclusive.model.tmdb.Film
import com.flixclusive.model.tmdb.TMDBEpisode
import com.flixclusive.model.tmdb.TvShow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val PLAYER_SCREEN_DELAY = 800

private fun isSameEpisode(
    film: Film,
    currentEpisode: TMDBEpisode?,
    episodeToPlay: TMDBEpisode?
) = film is TvShow
        && currentEpisode != null
        && episodeToPlay != null
        && currentEpisode.episodeId == episodeToPlay.episodeId


@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PlayerScreen(
    film: Film,
    episodeToPlay: TMDBEpisode?,
    isPlayerRunning: Boolean,
    isOverviewShown: Boolean,
    onBack: (isForced: Boolean) -> Unit,
) {
    val viewModel = playerScreenViewModel(
        args = PlayerScreenNavArgs(
            film = film,
            episodeToPlay = episodeToPlay
        )
    )

    val context = LocalContext.current.getActivity<ComponentActivity>()
    val scope = rememberCoroutineScope()

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val appSettings by viewModel.appSettings.collectAsStateWithLifecycle()
    val dialogState by viewModel.dialogState.collectAsStateWithLifecycle()
    val watchHistoryItem by viewModel.watchHistoryItem.collectAsStateWithLifecycle()
    val currentEpisodeSelected by viewModel.currentSelectedEpisode.collectAsStateWithLifecycle()

    LaunchedEffect(episodeToPlay, isPlayerRunning) {
        if (
            dialogState is SourceDataState.Success
            && (
                isSameEpisode(
                    film = film,
                    currentEpisode = currentEpisodeSelected,
                    episodeToPlay = episodeToPlay,
                ) || film.filmType == FilmType.MOVIE
                || !isPlayerRunning
            )
        ) return@LaunchedEffect

        if (episodeToPlay != null) {
            viewModel.onEpisodeClick(episodeToWatch = episodeToPlay)
        } else {
            viewModel.loadSourceData()
        }
    }

    if(
        dialogState !is SourceDataState.Success
        && dialogState !is SourceDataState.Idle
        && isPlayerRunning
    ) {
        SourceDataDialog(
            state = dialogState,
            onConsumeDialog = {
                if (dialogState !is SourceDataState.Success) {
                    viewModel.onConsumePlayerDialog()
                    onBack(true)
                }
            }
        )
    }

    AnimatedVisibility(
        visible = dialogState is SourceDataState.Success && isPlayerRunning,
        enter = fadeIn(animationSpec = tween(delayMillis = PLAYER_SCREEN_DELAY)),
        exit = fadeOut(animationSpec = tween(delayMillis = PLAYER_SCREEN_DELAY))
    ) {
        val sourceData = viewModel.sourceData

        val currentPlayerTitle = remember(currentEpisodeSelected) {
            formatPlayerTitle(film, currentEpisodeSelected)
        }
        var controlTimeoutVisibility by remember {
            mutableIntStateOf(PLAYER_CONTROL_VISIBILITY_TIMEOUT)
        }

        var seekMultiplier by remember { mutableLongStateOf(0L) }

        val isSubtitleStylePanelOpened = remember { mutableStateOf(false) }
        val isSyncSubtitlesPanelOpened = remember { mutableStateOf(false) }
        val isAudioAndSubtitlesPanelOpened = remember { mutableStateOf(false) }
        val isServerPanelOpened = remember { mutableStateOf(false) }
        val isPlaybackSpeedPanelOpened = remember { mutableStateOf(false) }

        val playerFocusRequester = remember { FocusRequester() }

        val isLastEpisode = remember(currentEpisodeSelected) {
            val lastSeason = watchHistoryItem?.seasons
            val lastEpisode = watchHistoryItem?.episodes?.get(lastSeason)

            currentEpisodeSelected?.season == lastSeason && currentEpisodeSelected?.episode == lastEpisode
        }

        val player = viewModel.player

        fun showControls(isShowing: Boolean) {
            val areSomeSheetsOpened = isSubtitleStylePanelOpened.value
                    || isSyncSubtitlesPanelOpened.value
                    || isAudioAndSubtitlesPanelOpened.value
                    || isServerPanelOpened.value
                    || isPlaybackSpeedPanelOpened.value

            val isLoading = !viewModel.player.hasBeenInitialized
                    || !viewModel.player.isPlaying
                    || viewModel.player.playbackState == Player.STATE_BUFFERING
                    || viewModel.player.playbackState == Player.STATE_ENDED


            controlTimeoutVisibility = if (!isShowing || areSomeSheetsOpened) {
                0
            } else if (isLoading) {
                Int.MAX_VALUE
            } else {
                PLAYER_CONTROL_VISIBILITY_TIMEOUT
            }
        }

        fun goBackToFilmScreen() {
            val playWhenReady = player.isPlaying
            player.pause()
            player.playWhenReady = playWhenReady

            onBack(false)

            scope.launch {
                delay(300) // Delay hack pfftt :3
                showControls(false)
            }
        }

        BackHandler(enabled = !isOverviewShown) {
            goBackToFilmScreen()
        }

        LaunchedEffect(Unit) {
            viewModel.resetUiState()
        }

        LaunchedEffect(isOverviewShown) {
            if (player.playWhenReady && !isOverviewShown) {
                player.play()
            }
        }

        /**
         *
         * Purpose (unless interacted):
         * Always show controls when player is paused.
         * Show it when player hasn't been initialized.
         * Don't show it if its locked and its buffering.
         * Show controls when buffering
         *
         * See local function (CTRL+F): [showControls]
         *
         * */
        LaunchedEffect(
            player.hasBeenInitialized,
            player.isPlaying,
            player.playbackState,
            isSubtitleStylePanelOpened.value,
            isServerPanelOpened.value,
            isAudioAndSubtitlesPanelOpened.value,
            isSyncSubtitlesPanelOpened.value,
            isPlaybackSpeedPanelOpened.value,
        ) {
            showControls(true)
        }

        LaunchedEffect(controlTimeoutVisibility) {
            if (controlTimeoutVisibility > 0) {
                viewModel.areControlsVisible = true
                delay(1000)
                controlTimeoutVisibility -= 1
            } else viewModel.areControlsVisible = false
        }

        CompositionLocalProvider(LocalPlayerManager provides viewModel.player) {
            ObserveNewLinksAndSubtitles(
                selectedSourceLink = uiState.selectedSourceLink,
                currentPlayerTitle = currentPlayerTitle,
                newLinks = sourceData.cachedLinks,
                newSubtitles = sourceData.cachedSubtitles,
                getSavedTimeForCurrentSourceData = {
                    viewModel.getSavedTimeForSourceData(currentEpisodeSelected).first
                }
            )

            provideLocalDirectionalFocusRequester {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface)
                ) {
                    AudioFocusManager(
                        activity = context,
                        preferredSeekAmount = appSettings.preferredSeekAmount,
                        isTv = true
                    )

                    LifecycleAwarePlayer(
                        isInTv = true,
                        areControlsVisible = viewModel.areControlsVisible,
                        isSubtitlesVisible = !isSubtitleStylePanelOpened.value,
                        onInitialize = {
                            viewModel.run {
                                val (currentPosition, _) = getSavedTimeForSourceData(
                                    currentEpisodeSelected
                                )

                                player.initialize()
                                sourceData.run {
                                    val getPossibleSourceLink = cachedLinks
                                        .getOrNull(uiState.selectedSourceLink)
                                        ?: cachedLinks.getOrNull(0)

                                    getPossibleSourceLink?.let {
                                        player.prepare(
                                            link = it,
                                            title = currentPlayerTitle,
                                            subtitles = cachedSubtitles,
                                            initialPlaybackPosition = currentPosition
                                        )
                                    }
                                }
                            }
                        },
                        onRelease = { isForceReleasing ->
                            viewModel.player.run {
                                viewModel.updateWatchHistory(
                                    currentTime = currentPosition,
                                    duration = duration
                                )

                                release(isForceReleasing = isForceReleasing)
                            }
                        },
                        modifier = Modifier
                            .handleDPadKeyEvents(
                                onEnter = {
                                    if (!viewModel.areControlsVisible) {
                                        showControls(true)
                                        player.run {
                                            if (isPlaying) {
                                                pause()
                                            } else play()
                                        }
                                    }
                                },
                                onUp = { showControls(true) },
                                onDown = { showControls(true) },
                                onLeft = { seekMultiplier -= 1 },
                                onRight = { seekMultiplier += 1 },
                            )
                            .focusRequester(playerFocusRequester)
                            .focusable()
                    )

                    PlaybackControls(
                        modifier = Modifier.fillMaxSize(),
                        appSettings = appSettings,
                        isSubtitleStylePanelOpened = isSubtitleStylePanelOpened,
                        isSyncSubtitlesPanelOpened = isSyncSubtitlesPanelOpened,
                        isAudioAndSubtitlesPanelOpened = isAudioAndSubtitlesPanelOpened,
                        isServerPanelOpened = isServerPanelOpened,
                        isPlaybackSpeedPanelOpened = isPlaybackSpeedPanelOpened,
                        isVisible = viewModel.areControlsVisible,
                        servers = sourceData.cachedLinks,
                        providers = viewModel.sourceProviders,
                        currentEpisodeSelected = currentEpisodeSelected,
                        stateProvider = { uiState },
                        dialogStateProvider = { dialogState },
                        playbackTitle = currentPlayerTitle,
                        isTvShow = film.filmType == FilmType.TV_SHOW,
                        isLastEpisode = isLastEpisode,
                        seekMultiplier = seekMultiplier,
                        showControls = { showControls(it) },
                        updateAppSettings = viewModel::updateAppSettings,
                        onServerChange = viewModel::onServerChange,
                        onProviderChange = viewModel::onProviderChange,
                        onSeekMultiplierChange = {
                            if (it == 0L) {
                                seekMultiplier = 0L
                                return@PlaybackControls
                            }

                            seekMultiplier += it
                        },
                        onBack = { goBackToFilmScreen() },
                        onNextEpisode = {
                            player.run {
                                viewModel.updateWatchHistory(
                                    currentTime = currentPosition,
                                    duration = duration
                                )

                                viewModel.onEpisodeClick()
                            }
                        },
                    )
                }
            }
        }
    }
}


