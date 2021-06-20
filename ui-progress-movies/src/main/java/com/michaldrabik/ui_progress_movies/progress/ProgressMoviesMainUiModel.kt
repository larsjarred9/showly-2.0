package com.michaldrabik.ui_progress_movies.progress

import com.michaldrabik.ui_base.UiModel

data class ProgressMoviesMainUiModel(
  val isSearching: Boolean? = null,
) : UiModel() {

  override fun update(newModel: UiModel) =
    (newModel as ProgressMoviesMainUiModel).copy(
      isSearching = newModel.isSearching ?: isSearching,
    )
}
