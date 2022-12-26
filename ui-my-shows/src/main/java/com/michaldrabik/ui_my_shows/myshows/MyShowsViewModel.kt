package com.michaldrabik.ui_my_shows.myshows

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.michaldrabik.common.Config
import com.michaldrabik.repository.images.ShowImagesProvider
import com.michaldrabik.repository.settings.SettingsRepository
import com.michaldrabik.ui_base.events.EventsManager
import com.michaldrabik.ui_base.events.ReloadData
import com.michaldrabik.ui_base.events.TraktSyncAuthError
import com.michaldrabik.ui_base.events.TraktSyncError
import com.michaldrabik.ui_base.events.TraktSyncSuccess
import com.michaldrabik.ui_base.utilities.events.Event
import com.michaldrabik.ui_base.utilities.extensions.SUBSCRIBE_STOP_TIMEOUT
import com.michaldrabik.ui_base.utilities.extensions.findReplace
import com.michaldrabik.ui_model.Image
import com.michaldrabik.ui_model.ImageType
import com.michaldrabik.ui_model.ImageType.POSTER
import com.michaldrabik.ui_model.MyShowsSection
import com.michaldrabik.ui_model.MyShowsSection.ALL
import com.michaldrabik.ui_model.MyShowsSection.RECENTS
import com.michaldrabik.ui_model.Show
import com.michaldrabik.ui_model.SortOrder
import com.michaldrabik.ui_model.SortType
import com.michaldrabik.ui_model.TraktRating
import com.michaldrabik.ui_my_shows.main.FollowedShowsUiState
import com.michaldrabik.ui_my_shows.myshows.cases.MyShowsLoadShowsCase
import com.michaldrabik.ui_my_shows.myshows.cases.MyShowsRatingsCase
import com.michaldrabik.ui_my_shows.myshows.cases.MyShowsSortingCase
import com.michaldrabik.ui_my_shows.myshows.cases.MyShowsTranslationsCase
import com.michaldrabik.ui_my_shows.myshows.recycler.MyShowsItem
import com.michaldrabik.ui_my_shows.myshows.recycler.MyShowsItem.Type
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import com.michaldrabik.ui_base.events.Event as EventSync

@HiltViewModel
class MyShowsViewModel @Inject constructor(
  private val loadShowsCase: MyShowsLoadShowsCase,
  private val sortingCase: MyShowsSortingCase,
  private val ratingsCase: MyShowsRatingsCase,
  private val translationsCase: MyShowsTranslationsCase,
  private val settingsRepository: SettingsRepository,
  private val imagesProvider: ShowImagesProvider,
  private val eventsManager: EventsManager,
) : ViewModel() {

  private var loadItemsJob: Job? = null

  private val itemsState = MutableStateFlow<List<MyShowsItem>?>(null)
  private val itemsUpdateState = MutableStateFlow<Event<List<Type>?>?>(null)

  private var searchQuery: String? = null

  init {
    viewModelScope.launch { eventsManager.events.collect { onEvent(it) } }
  }

  fun onParentState(state: FollowedShowsUiState) {
    when {
      this.searchQuery != state.searchQuery -> {
        this.searchQuery = state.searchQuery
        val resetScrolls =
          if (state.searchQuery.isNullOrBlank()) listOf(Type.ALL_SHOWS_ITEM)
          else emptyList()
        loadShows(resetScroll = resetScrolls)
      }
    }
  }

  fun loadShows(resetScroll: List<Type>? = null) {
    loadItemsJob?.cancel()
    loadItemsJob = viewModelScope.launch {
      val settings = settingsRepository.load()
      val ratings = ratingsCase.loadRatings()

      val shows = loadShowsCase.loadAllShows()
        .map {
          toListItemAsync(
            itemType = Type.ALL_SHOWS_ITEM,
            show = it,
            type = POSTER,
            userRating = ratings[it.ids.trakt]
          )
        }
        .awaitAll()

      val seasons = loadShowsCase.loadSeasonsForShows(shows.map { it.show.traktId })
      val allShows = loadShowsCase.filterSectionShows(
        allShows = shows,
        allSeasons = seasons,
        searchQuery = searchQuery
      )

      val recentShows = if (settings.myShowsRecentIsEnabled) {
        loadShowsCase.loadRecentShows().map {
          toListItemAsync(Type.RECENT_SHOWS, it, ImageType.FANART, ratings[it.ids.trakt])
        }.awaitAll()
      } else {
        emptyList()
      }

      val isNotSearching = searchQuery.isNullOrBlank()
      val listItems = mutableListOf<MyShowsItem>()
      listItems.run {
        if (isNotSearching && recentShows.isNotEmpty()) {
          add(MyShowsItem.createHeader(RECENTS, recentShows.count(), null))
          add(MyShowsItem.createRecentsSection(recentShows))
        }
        add(
          MyShowsItem.createHeader(
            section = settingsRepository.filters.myShowsType,
            itemCount = allShows.count(),
            sortOrder = sortingCase.loadSectionSortOrder(ALL)
          )
        )
        addAll(allShows)
      }

      itemsState.value = listItems
      itemsUpdateState.value = Event(resetScroll)
    }
  }

  fun setSectionSortOrder(section: MyShowsSection, sortOrder: SortOrder, sortType: SortType) {
    viewModelScope.launch {
      sortingCase.setSectionSortOrder(section, sortOrder, sortType)
      loadShows()
    }
  }

  fun loadMissingImage(item: MyShowsItem, force: Boolean) {
    viewModelScope.launch {
      updateItem(item.copy(isLoading = true))
      try {
        val image = imagesProvider.loadRemoteImage(item.show, item.image.type, force)
        updateItem(item.copy(isLoading = false, image = image))
      } catch (t: Throwable) {
        updateItem(item.copy(isLoading = false, image = Image.createUnavailable(item.image.type)))
      }
    }
  }

  fun loadMissingTranslation(item: MyShowsItem) {
    if (item.translation != null || translationsCase.language == Config.DEFAULT_LANGUAGE) return
    viewModelScope.launch {
      try {
        val translation = translationsCase.loadTranslation(item.show, false)
        updateItem(item.copy(translation = translation))
      } catch (error: Throwable) {
        Timber.e(error)
      }
    }
  }

  private fun updateItem(new: MyShowsItem) {
    val items = uiState.value.items?.toMutableList()
    items?.findReplace(new) { it.isSameAs(new) }
    itemsState.value = items
  }

  private fun CoroutineScope.toListItemAsync(
    itemType: Type,
    show: Show,
    type: ImageType = POSTER,
    userRating: TraktRating?,
  ) = async {
    val image = imagesProvider.findCachedImage(show, type)
    val translation = translationsCase.loadTranslation(show, true)
    MyShowsItem(itemType, null, null, show, image, false, translation, userRating?.rating)
  }

  private fun onEvent(event: EventSync) =
    when (event) {
      is TraktSyncSuccess -> loadShows()
      is TraktSyncError -> loadShows()
      is TraktSyncAuthError -> loadShows()
      is ReloadData -> loadShows()
      else -> Unit
    }

  val uiState = combine(
    itemsState,
    itemsUpdateState
  ) { itemsState, itemsUpdateState ->
    MyShowsUiState(
      items = itemsState,
      resetScrollMap = itemsUpdateState
    )
  }.stateIn(
    scope = viewModelScope,
    started = SharingStarted.WhileSubscribed(SUBSCRIBE_STOP_TIMEOUT),
    initialValue = MyShowsUiState()
  )
}
