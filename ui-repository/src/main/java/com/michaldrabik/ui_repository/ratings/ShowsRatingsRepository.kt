package com.michaldrabik.ui_repository.ratings

import com.michaldrabik.common.di.AppScope
import com.michaldrabik.common.extensions.nowUtc
import com.michaldrabik.network.Cloud
import com.michaldrabik.ui_model.Episode
import com.michaldrabik.ui_model.IdTrakt
import com.michaldrabik.ui_model.Show
import com.michaldrabik.ui_model.TraktRating
import com.michaldrabik.ui_repository.mappers.Mappers
import org.threeten.bp.ZonedDateTime
import javax.inject.Inject

@AppScope
class ShowsRatingsRepository @Inject constructor(
  private val cloud: Cloud,
  private val mappers: Mappers
) {

  private var showsCache: MutableList<TraktRating>? = null
  private var episodesCache: MutableList<TraktRating>? = null

  suspend fun preloadShowsRatings(token: String) {
    if (showsCache == null) {
      val ratings = cloud.traktApi.fetchShowsRatings(token)
      showsCache = ratings.map { rate ->
        val id = IdTrakt(rate.show.ids.trakt ?: -1)
        val date = rate.rated_at?.let { ZonedDateTime.parse(it) } ?: nowUtc()
        TraktRating(id, rate.rating, date)
      }.toMutableList()
    }
  }

  suspend fun loadShowsRatings(token: String): List<TraktRating> {
    preloadShowsRatings(token)
    return showsCache?.toList() ?: emptyList()
  }

  suspend fun loadRating(token: String, show: Show): TraktRating? {
    preloadShowsRatings(token)
    return showsCache?.find { it.idTrakt == show.ids.trakt }
  }

  suspend fun loadRating(token: String, episode: Episode): TraktRating? {
    if (episodesCache == null) {
      val ratings = cloud.traktApi.fetchEpisodesRatings(token)
      episodesCache = ratings.map {
        val id = IdTrakt(it.episode.ids.trakt ?: -1)
        TraktRating(id, it.rating)
      }.toMutableList()
    }
    return episodesCache?.find { it.idTrakt == episode.ids.trakt }
  }

  suspend fun addRating(token: String, show: Show, rating: Int) {
    cloud.traktApi.postRating(
      token,
      mappers.show.toNetwork(show),
      rating
    )
    showsCache?.run {
      val index = indexOfFirst { it.idTrakt == show.ids.trakt }
      if (index != -1) removeAt(index)
      add(TraktRating(show.ids.trakt, rating))
    }
  }

  suspend fun addRating(token: String, episode: Episode, rating: Int) {
    cloud.traktApi.postRating(
      token,
      mappers.episode.toNetwork(episode),
      rating
    )
    episodesCache?.run {
      val index = indexOfFirst { it.idTrakt == episode.ids.trakt }
      if (index != -1) removeAt(index)
      add(TraktRating(episode.ids.trakt, rating))
    }
  }

  suspend fun deleteRating(token: String, show: Show) {
    cloud.traktApi.deleteRating(
      token,
      mappers.show.toNetwork(show)
    )
    showsCache?.run {
      val index = indexOfFirst { it.idTrakt == show.ids.trakt }
      if (index != -1) removeAt(index)
    }
  }

  suspend fun deleteRating(token: String, episode: Episode) {
    cloud.traktApi.deleteRating(
      token,
      mappers.episode.toNetwork(episode)
    )
    episodesCache?.run {
      val index = indexOfFirst { it.idTrakt == episode.ids.trakt }
      if (index != -1) removeAt(index)
    }
  }

  fun clear() {
    showsCache = null
    episodesCache = null
  }
}
