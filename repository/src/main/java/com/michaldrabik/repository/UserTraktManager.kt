package com.michaldrabik.repository

import com.michaldrabik.common.dispatchers.CoroutineDispatchers
import com.michaldrabik.common.errors.ShowlyError
import com.michaldrabik.data_local.database.model.User
import com.michaldrabik.data_local.sources.UserLocalDataSource
import com.michaldrabik.data_local.utilities.TransactionsProvider
import com.michaldrabik.data_remote.token.TokenProvider
import com.michaldrabik.data_remote.trakt.TraktRemoteDataSource
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import com.michaldrabik.data_remote.trakt.model.User as UserModel

@Singleton
class UserTraktManager @Inject constructor(
  private val dispatchers: CoroutineDispatchers,
  private val remoteSource: TraktRemoteDataSource,
  private val userLocalSource: UserLocalDataSource,
  private val transactions: TransactionsProvider,
  private val tokenProvider: TokenProvider
) {

  fun isAuthorized() = tokenProvider.getToken() != null

  fun checkAuthorization() {
    if (tokenProvider.getToken() == null) {
      throw ShowlyError.UnauthorizedError("Authorization needed.")
    }
  }

  suspend fun authorize(authCode: String) {
    val tokens = remoteSource.fetchAuthTokens(authCode)
    tokenProvider.saveTokens(tokens.access_token, tokens.refresh_token)
    val user = remoteSource.fetchMyProfile()
    saveUser(user)
  }

  suspend fun revokeToken() {
    val token = tokenProvider.getToken()
    tokenProvider.revokeToken()
    try {
      if (!token.isNullOrBlank()) {
        remoteSource.revokeAuthTokens(token)
      }
    } catch (error: Throwable) {
      // Just log error as revoke token call is fully optional.
      Timber.w("Error while revoking token: $error")
    }
  }

  suspend fun getUsername() = userLocalSource.get()?.traktUsername ?: ""

  private suspend fun saveUser(userModel: UserModel) {
    transactions.withTransaction {
      val user = userLocalSource.get()
      userLocalSource.upsert(
        user?.copy(
          traktUsername = userModel.username
        ) ?: User(
          traktToken = "",
          traktRefreshToken = "",
          traktTokenTimestamp = 0,
          traktUsername = userModel.username,
          tvdbToken = "",
          tvdbTokenTimestamp = 0,
          redditToken = "",
          redditTokenTimestamp = 0
        )
      )
    }
  }
}
