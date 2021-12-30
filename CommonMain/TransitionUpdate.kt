package com.tryformation.geofencing

import realm.models.LocationModel
import realm.models.ZoneModel

sealed class TransitionUpdate<DateType>(
    open val instant: DateType,
    open val location: LocationModel<DateType>,
    open val geofence: ZoneModel<DateType, *, *, *, *>,
    open val updateType: Updates
) {

    class Deprecation<DateType>(
        override val instant: DateType,
        override val location: LocationModel<DateType>,
        override val geofence: ZoneModel<DateType, *, *, *, *>,
        override val updateType: Updates = Updates.DEPR
    ) :
        TransitionUpdate<DateType>(instant, location, geofence, updateType)

    class Enter<DateType>(
        override val instant: DateType,
        override val location: LocationModel<DateType>,
        override val geofence: ZoneModel<DateType, *, *, *, *>,
        override val updateType: Updates = Updates.ENTER
    ) :
        TransitionUpdate<DateType>(instant, location, geofence, updateType)

    class Exit<DateType>(
        override val instant: DateType,
        override val location: LocationModel<DateType>,
        override val geofence: ZoneModel<DateType, *, *, *, *>,
        override val updateType: Updates = Updates.EXIT
    ) :
        TransitionUpdate<DateType>(instant, location, geofence, updateType)

    class Extension<DateType>(
        override val instant: DateType,
        override val location: LocationModel<DateType>,
        override val geofence: ZoneModel<DateType, *, *, *, *>,
        override val updateType: Updates = Updates.EXTE
    ) :
        TransitionUpdate<DateType>(instant, location, geofence, updateType)
}
