package com.shizq.bika.core.data.di

import com.shizq.bika.core.data.platform.AndroidFileShareProvider
import com.shizq.bika.core.data.platform.FileShareProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import jakarta.inject.Singleton

/**
 * 平台能力（Intent / FileProvider 之类的实现细节）的绑定。
 *
 * 这些绑定原先和"版本号获取 / 更新包安装"挤在同一个 UpdateModule 里，
 * 更新功能整体删掉后，与更新无关的 [FileShareProvider] 也跟着丢了绑定，
 * 表现是下载导出分享处编译不过。这里给它一个稳定的归宿：
 * 只要是"Android 平台细节的实现类绑定"，就放这里，不要挂靠在某个业务模块下。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class PlatformModule {
    @Binds
    @Singleton
    abstract fun bindFileShareProvider(
        impl: AndroidFileShareProvider,
    ): FileShareProvider
}
