package com.tryformation.geofencing
import kotlinx.coroutines.CoroutineScope


expect fun <T> runTest(block: suspend () -> T)
expect suspend fun likeLaunch(block: suspend () -> Unit,scope:CoroutineScope)