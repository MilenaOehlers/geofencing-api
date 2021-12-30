package com.tryformation.geofencing

import kotlinx.coroutines.flow.Flow
import realm.models.LocationModel
import realm.models.ZoneModel

interface GeofencingApiInbounds<DateType> {

    val initialLocation: LocationModel<DateType>
    val initialTransitions: Map<ZoneModel<DateType, LocationModel<DateType>, *, *, *>, DateType?>

    val timerChannel: Flow<Long>
    val locationUpdateChannel: Flow<LocationModel<DateType>>
    val geofenceFlow: Flow<Pair<ZoneModel<DateType, LocationModel<DateType>, *, *, *>, Delta>>

    enum class Delta {
        INSERT, UPDATE, DELETE
    }
}
