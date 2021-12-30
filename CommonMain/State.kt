package com.tryformation.geofencing

import realm.models.LocationModel
import realm.models.ZoneModel

internal class State<DateType>(initialTransitions: Map<ZoneModel<DateType, *, *, *, *>, Boolean>) {
    var allGeofences: MutableMap<String, ZoneModel<DateType, *, *, *, *>> =
        initialTransitions.map { it.key._id!! to it.key }.toMap().toMutableMap()
    var currentEnteredGeofences: MutableSet<String> =
        initialTransitions.toList().filter { it.second }.map { it.first._id!! }.toMutableSet()
    var lastLocation: LocationModel<DateType>? = null
    var timerBool: Boolean = false
}
