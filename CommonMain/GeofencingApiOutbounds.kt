package com.tryformation.geofencing

import kotlinx.coroutines.channels.Channel
import realm.models.LocationModel
import realm.models.ZoneModel

interface GeofencingApiOutbounds<DateType> {
    val transitionUpdateChannel: Channel<TransitionUpdate<DateType>>
    suspend fun getEnteredGeofences(): List<ZoneModel<DateType, *, *, *, *>>
    suspend fun isGeofenceEntered(zone: ZoneModel<DateType, LocationModel<DateType>, *, *, *>): Boolean
    suspend fun isGeofenceEntered(geofenceId: String): Boolean
}
