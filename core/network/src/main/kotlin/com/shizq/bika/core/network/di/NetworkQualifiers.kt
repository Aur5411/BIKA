package com.shizq.bika.core.network.di

import jakarta.inject.Qualifier

/**
 * 图片链路专用客户端的限定符。
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ImageClient

/** DNS 解析专用客户端的限定符。 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DnsClient
