// SMSRelay para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smsrelay.di

import com.capicua.smsrelay.data.repository.LogRepository
import com.capicua.smsrelay.data.repository.LogRepositoryImpl
import com.capicua.smsrelay.data.repository.ReenvioRepository
import com.capicua.smsrelay.data.repository.ReenvioRepositoryImpl
import com.capicua.smsrelay.data.repository.ReglaRepository
import com.capicua.smsrelay.data.repository.ReglaRepositoryImpl
import com.capicua.smsrelay.data.repository.SmsRepository
import com.capicua.smsrelay.data.repository.SmsRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindSmsRepository(impl: SmsRepositoryImpl): SmsRepository

    @Binds
    @Singleton
    abstract fun bindReglaRepository(impl: ReglaRepositoryImpl): ReglaRepository

    @Binds
    @Singleton
    abstract fun bindReenvioRepository(impl: ReenvioRepositoryImpl): ReenvioRepository

    @Binds
    @Singleton
    abstract fun bindLogRepository(impl: LogRepositoryImpl): LogRepository
}
