package com.michaldrabik.ui_base.trakt.imports

import androidx.room.withTransaction
import com.michaldrabik.common.Config
import com.michaldrabik.common.di.AppScope
import com.michaldrabik.common.extensions.nowUtcMillis
import com.michaldrabik.network.Cloud
import com.michaldrabik.network.trakt.model.SyncItem
import com.michaldrabik.storage.database.AppDatabase
import com.michaldrabik.storage.database.model.Episode
import com.michaldrabik.storage.database.model.MyMovie
import com.michaldrabik.storage.database.model.MyShow
import com.michaldrabik.storage.database.model.Season
import com.michaldrabik.ui_base.images.MovieImagesProvider
import com.michaldrabik.ui_base.images.ShowImagesProvider
import com.michaldrabik.ui_base.trakt.TraktSyncRunner
import com.michaldrabik.ui_model.IdTrakt
import com.michaldrabik.ui_model.ImageType.FANART
import com.michaldrabik.ui_model.Movie
import com.michaldrabik.ui_model.Show
import com.michaldrabik.ui_repository.SettingsRepository
import com.michaldrabik.ui_repository.TraktAuthToken
import com.michaldrabik.ui_repository.TranslationsRepository
import com.michaldrabik.ui_repository.UserTraktManager
import com.michaldrabik.ui_repository.UserTvdbManager
import com.michaldrabik.ui_repository.mappers.Mappers
import kotlinx.coroutines.delay
import timber.log.Timber
import javax.inject.Inject

@AppScope
class TraktImportWatchedRunner @Inject constructor(
  private val cloud: Cloud,
  private val database: AppDatabase,
  private val mappers: Mappers,
  private val showImagesProvider: ShowImagesProvider,
  private val movieImagesProvider: MovieImagesProvider,
  private val userTvdbManager: UserTvdbManager,
  private val translationsRepository: TranslationsRepository,
  private val settingsRepository: SettingsRepository,
  userTraktManager: UserTraktManager
) : TraktSyncRunner(userTraktManager) {

  override suspend fun run(): Int {
    Timber.d("Initialized.")
    isRunning = true

    var syncedCount = 0
    val authToken = checkAuthorization()

    resetRetries()
    syncedCount += runShows(authToken)

    resetRetries()
    syncedCount += runMovies(authToken)

    isRunning = false
    Timber.d("Finished with success.")

    return syncedCount
  }

  private suspend fun runShows(authToken: TraktAuthToken): Int =
    try {
      importWatchedShows(authToken.token)
    } catch (error: Throwable) {
      if (retryCount < MAX_RETRY_COUNT) {
        Timber.w("runShows HTTP failed. Will retry in $RETRY_DELAY_MS ms... $error")
        retryCount += 1
        delay(RETRY_DELAY_MS)
        runShows(authToken)
      } else {
        isRunning = false
        throw error
      }
    }

  private suspend fun runMovies(authToken: TraktAuthToken): Int {
    if (!settingsRepository.isMoviesEnabled()) {
      Timber.d("Movies are disabled. Exiting...")
      return 0
    }
    return try {
      importWatchedMovies(authToken.token)
    } catch (error: Throwable) {
      if (retryCount < MAX_RETRY_COUNT) {
        Timber.w("runMovies HTTP failed. Will retry in $RETRY_DELAY_MS ms... $error")
        retryCount += 1
        delay(RETRY_DELAY_MS)
        runMovies(authToken)
      } else {
        isRunning = false
        throw error
      }
    }
  }

  private suspend fun importWatchedShows(token: String): Int {
    Timber.d("Importing watched shows...")

    checkTvdbAuth()
    val hiddenShowsIds = cloud.traktApi.fetchHiddenShows(token)
      .filter { it.show != null }
      .map { it.show!!.ids?.trakt }

    val syncResults = cloud.traktApi.fetchSyncWatchedShows(token, "full")
      .filter { it.show != null }
      .filter { it.show?.ids?.trakt !in hiddenShowsIds }
      .distinctBy { it.show?.ids?.trakt }

    val myShowsIds = database.myShowsDao().getAllTraktIds()
    val watchlistShowsIds = database.watchlistShowsDao().getAllTraktIds()
    val archiveShowsIds = database.archiveShowsDao().getAllTraktIds()
    val traktSyncLogs = database.traktSyncLogDao().getAllShows()

    syncResults
      .forEachIndexed { index, result ->
        delay(50)

        val showUi = mappers.show.fromNetwork(result.show!!)
        progressListener?.invoke(showUi.title, index, syncResults.size)

        Timber.d("Processing \'${showUi.title}\'...")

        val log = traktSyncLogs.firstOrNull { it.idTrakt == result.show?.ids?.trakt }
        if (result.lastUpdateMillis() == (log?.syncedAt ?: 0)) {
          Timber.d("Nothing changed in \'${result.show!!.title}\'. Skipping...")
          return@forEachIndexed
        }

        try {
          val showId = result.show!!.ids!!.trakt!!
          val (seasons, episodes) = loadSeasons(showId, result)

          database.withTransaction {
            if (showId !in myShowsIds && showId !in archiveShowsIds) {
              val show = mappers.show.fromNetwork(result.show!!)
              val showDb = mappers.show.toDatabase(show)

              val timestamp = result.lastWatchedMillis()
              val myShow = MyShow.fromTraktId(showDb.idTrakt, nowUtcMillis(), timestamp)
              database.showsDao().upsert(listOf(showDb))
              database.myShowsDao().insert(listOf(myShow))

              loadImage(show)

              if (showId in watchlistShowsIds) {
                database.watchlistShowsDao().deleteById(showId)
              }
            }
            database.seasonsDao().upsert(seasons)
            database.episodesDao().upsert(episodes)

            database.traktSyncLogDao().upsertShow(showId, result.lastUpdateMillis())
          }

          updateTranslation(showUi)
        } catch (t: Throwable) {
          Timber.w("Processing \'${result.show!!.title}\' failed. Skipping...")
        }
      }

    return syncResults.size
  }

  private suspend fun loadSeasons(showId: Long, item: SyncItem): Pair<List<Season>, List<Episode>> {
    val remoteSeasons = cloud.traktApi.fetchSeasons(showId)
    val localSeasonsIds = database.seasonsDao().getAllWatchedIdsForShows(listOf(showId))
    val localEpisodesIds = database.episodesDao().getAllWatchedIdsForShows(listOf(showId))

    val seasons = remoteSeasons
      .filterNot { localSeasonsIds.contains(it.ids?.trakt) }
      .map { mappers.season.fromNetwork(it) }
      .map { remoteSeason ->
        val isWatched = item.seasons?.any {
          it.number == remoteSeason.number && it.episodes?.size == remoteSeason.episodes.size
        } ?: false
        mappers.season.toDatabase(remoteSeason, IdTrakt(showId), isWatched)
      }

    val episodes = remoteSeasons.flatMap { season ->
      season.episodes
        ?.filterNot { localEpisodesIds.contains(it.ids?.trakt) }
        ?.map { episode ->
          val isWatched = item.seasons
            ?.find { it.number == season.number }?.episodes
            ?.find { it.number == episode.number } != null

          val seasonDb = mappers.season.fromNetwork(season)
          val episodeDb = mappers.episode.fromNetwork(episode)
          mappers.episode.toDatabase(episodeDb, seasonDb, IdTrakt(showId), isWatched)
        } ?: emptyList()
    }

    return Pair(seasons, episodes)
  }

  private suspend fun importWatchedMovies(token: String): Int {
    Timber.d("Importing watched movies...")

    val hiddenIds = cloud.traktApi.fetchHiddenMovies(token)
      .filter { it.movie != null }
      .map { it.movie!!.ids?.trakt }

    val syncResults = cloud.traktApi.fetchSyncWatchedMovies(token, "full")
      .filter { it.movie != null }
      .filter { it.movie?.ids?.trakt !in hiddenIds }
      .distinctBy { it.movie?.ids?.trakt }

    val myMoviesIds = database.myMoviesDao().getAllTraktIds()
    val watchlistMoviesIds = database.watchlistMoviesDao().getAllTraktIds()

    syncResults
      .forEachIndexed { index, result ->
        delay(50)
        Timber.d("Processing \'${result.movie!!.title}\'...")
        val movieUi = mappers.movie.fromNetwork(result.movie!!)
        progressListener?.invoke(movieUi.title, index, syncResults.size)

        try {
          val movieId = result.movie!!.ids!!.trakt!!

          database.withTransaction {
            if (movieId !in myMoviesIds) {
              val movie = mappers.movie.fromNetwork(result.movie!!)
              val movieDb = mappers.movie.toDatabase(movie)

              val timestamp = result.lastWatchedMillis()
              val myMovie = MyMovie.fromTraktId(movieDb.idTrakt, nowUtcMillis(), timestamp)
              database.moviesDao().upsert(listOf(movieDb))
              database.myMoviesDao().insert(listOf(myMovie))

              loadImage(movie)

              if (movieId in watchlistMoviesIds) {
                database.watchlistMoviesDao().deleteById(movieId)
              }
            }
          }

          updateTranslation(movieUi)
        } catch (t: Throwable) {
          Timber.w("Processing \'${result.movie!!.title}\' failed. Skipping...")
        }
      }

    return syncResults.size
  }

  private suspend fun loadImage(show: Show) {
    try {
      if (!userTvdbManager.isAuthorized()) return
      showImagesProvider.loadRemoteImage(show, FANART)
    } catch (t: Throwable) {
      // NOOP Ignore image for now. It will be fetched later if needed.
    }
  }

  private suspend fun loadImage(movie: Movie) {
    try {
      movieImagesProvider.loadRemoteImage(movie, FANART)
    } catch (t: Throwable) {
      // NOOP Ignore image for now. It will be fetched later if needed.
    }
  }

  private suspend fun checkTvdbAuth() {
    try {
      userTvdbManager.checkAuthorization()
    } catch (t: Throwable) {
      // Ignore for now
    }
  }

  private suspend fun updateTranslation(showUi: Show) {
    try {
      val language = settingsRepository.getLanguage()
      if (language != Config.DEFAULT_LANGUAGE) {
        Timber.d("Fetching \'${showUi.title}\' translation...")
        translationsRepository.updateLocalTranslation(showUi, language)
      }
    } catch (error: Throwable) {
      Timber.w("Processing \'${showUi.title}\' translation failed. Skipping translation...")
    }
  }

  private suspend fun updateTranslation(movieUi: Movie) {
    try {
      val language = settingsRepository.getLanguage()
      if (language != Config.DEFAULT_LANGUAGE) {
        Timber.d("Fetching \'${movieUi.title}\' translation...")
        translationsRepository.updateLocalTranslation(movieUi, language)
      }
    } catch (error: Throwable) {
      Timber.w("Processing \'${movieUi.title}\' translation failed. Skipping translation...")
    }
  }
}
