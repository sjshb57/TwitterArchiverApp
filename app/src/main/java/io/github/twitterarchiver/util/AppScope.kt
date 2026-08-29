package io.github.twitterarchiver.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

object AppScope : CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.IO)
