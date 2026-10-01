package com.shizq.bika

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shizq.bika.core.datastore.UserCredentialsDataSource
import com.shizq.bika.core.datastore.UserPreferencesDataSource
import com.shizq.bika.core.message.MessageReporter
import com.shizq.bika.core.model.theme.DarkThemeConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import jakarta.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class MainActivityViewModel @Inject constructor(
    private val userCredentialsDataSource: UserCredentialsDataSource,
    userPreferencesDataSource: UserPreferencesDataSource,
    private val messageReporter: MessageReporter,
) : ViewModel() {
    private val loginStateFlow = userCredentialsDataSource.userData
        .map { !it.token.isNullOrBlank() }

    private val themeConfigFlow = userPreferencesDataSource.userData
        .map { it.theme.darkThemeConfig }
    private val fontScaleFlow = userPreferencesDataSource.userData
        .map { it.app.fontScale }

    /**
     * 启动期状态：登录与否、深色主题、字体缩放。
     *
     * ## 为什么是 `Eagerly` 而不是 `WhileSubscribed`
     *
     * Activity 用它决定**要不要继续盖着启动图**（
     * `splashScreen.setKeepOnScreenCondition { uiState.value.shouldKeepSplashScreen() }`），
     * 而 `shouldKeepSplashScreen()` 在状态是 [MainActivityUiState.Loading] 时为 true。
     *
     * `WhileSubscribed` 下，上游（三个 DataStore flow）**要等到有人订阅才开始收集**，
     * 而第一个真正的订阅者是 Compose 内容里的 `collectAsStateWithLifecycle`——
     * 也就是说 DataStore 的首次读盘 + 反序列化被排在了 Compose 首帧**之后**，
     * 启动图的停留时间里白白多出这一段。
     *
     * `Eagerly` 让它在 ViewModel 构造时就发起，与 Activity 的创建、Compose 的
     * 首次组合并行完成，启动图能早一截撤掉。
     *
     * `splashScreen.setKeepOnScreenCondition` 是逐帧读 `.value` 的，本身不构成订阅，
     * 所以这一点改动是必要的——不是调参。
     */
    val uiState: StateFlow<MainActivityUiState> = combine(
        loginStateFlow,
        themeConfigFlow,
        fontScaleFlow,
    ) { isLoggedIn, darkThemeConfig, fontScale ->
        MainActivityUiState.Success(
            isLoggedIn = isLoggedIn,
            darkThemeConfig = darkThemeConfig,
            fontScale = fontScale,
        )
    }.stateIn(
        scope = viewModelScope,
        initialValue = MainActivityUiState.Loading,
        started = SharingStarted.Eagerly,
    )

    /**
     * 显式登出。
     *
     * 只清 token，保留用户名/密码，使登录页仍能预填。token 变空后
     * [loginStateFlow] 会把 [MainActivityUiState.Success.isLoggedIn] 翻成 false，
     * 根节点据此切回认证图——登出不需要导航事件。
     *
     * 在 `viewModelScope` 而不是 composition 的 scope 里执行：登出会立刻把当前
     * 页面从组合里移除，挂在页面上的协程会被取消，写入可能半途而废。
     */
    fun logout() {
        viewModelScope.launch {
            // 先清提示：排队中的消息可能带着指向已登出账号的 action，
            // 切回认证图后弹出来既无意义也可能触发对已失效会话的调用
            messageReporter.clear()
            userCredentialsDataSource.setToken(null)
        }
    }
}

sealed interface MainActivityUiState {
    data object Loading : MainActivityUiState

    data class Success(
        val isLoggedIn: Boolean,
        val darkThemeConfig: DarkThemeConfig,
        val fontScale: Float,
    ) : MainActivityUiState {
        override fun shouldUseDarkTheme(isSystemDarkTheme: Boolean): Boolean =
            when (darkThemeConfig) {
                DarkThemeConfig.FOLLOW_SYSTEM -> isSystemDarkTheme
                DarkThemeConfig.ON -> true
                DarkThemeConfig.OFF -> false
            }
    }

    fun shouldKeepSplashScreen() = this is Loading

    /**
     * Returns `true` if the dynamic color is disabled.
     */
    val shouldDisableDynamicTheming: Boolean get() = true

    /**
     * Returns `true` if the Android theme should be used.
     */
    val shouldUseAndroidTheme: Boolean get() = false
    fun shouldUseDarkTheme(isSystemDarkTheme: Boolean) = isSystemDarkTheme
}
