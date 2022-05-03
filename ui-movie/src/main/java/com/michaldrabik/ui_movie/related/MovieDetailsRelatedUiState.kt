package com.michaldrabik.ui_movie.related

import com.michaldrabik.ui_movie.related.recycler.RelatedListItem

data class MovieDetailsRelatedUiState(
  val relatedMovies: List<RelatedListItem>? = null,
)
