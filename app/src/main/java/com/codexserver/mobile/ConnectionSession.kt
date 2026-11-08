package com.codexserver.mobile

import android.app.Application
import androidx.annotation.MainThread
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/** One session per process: finishing the Activity must not cancel background recovery. */
internal object ConnectionSession : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()

    @MainThread
    fun get(application: Application): MobileViewModel = ViewModelProvider(
        this, ViewModelProvider.AndroidViewModelFactory.getInstance(application)
    )[MobileViewModel::class.java]
}
