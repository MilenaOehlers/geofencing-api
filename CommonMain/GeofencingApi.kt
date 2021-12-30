package com.tryformation.geofencing

// import java.util.concurrent.Executors
import com.jillesvangurp.geo.GeoGeometry.Companion.polygonContains
import com.tryformation.clock.DateTypeConversion
import com.tryformation.clock.currentTimeInMillis
import kotlin.time.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import realm.models.LocationModel
import realm.models.ZoneModel
import realm.types.DelimitationPattern
import kotlin.math.pow
import kotlin.collections.*

@OptIn(ExperimentalCoroutinesApi::class)
abstract class GeofencingApi<DateType>(val inbounds: GeofencingApiInbounds<DateType>, val  dateTypeConversion: DateTypeConversion<DateType>) :
    GeofencingApiOutbounds<DateType> {

    open var lastTimerInstant: Long = 0
    open lateinit var lastLocation: LocationModel<DateType>
    // contains all zones + non null validity if currently entered
    // the instant contains the "changedOn" of the last LocationUpdate
    open lateinit var state: MutableMap<ZoneModel<DateType, LocationModel<DateType>, *, *, *>, DateType?>
    override val transitionUpdateChannel = Channel<TransitionUpdate<DateType>>(UNLIMITED)

    abstract suspend fun onApiStart(): Job
    abstract suspend fun onApiClose()

    internal fun broadcastTransitionUpdate(update: Updates, instant: DateType, zone: ZoneModel<DateType, LocationModel<DateType>, *, *, *>) {
        when (update) {
            Updates.DEPR -> transitionUpdateChannel.offer(TransitionUpdate.Deprecation(instant, lastLocation, zone))
            Updates.ENTER -> transitionUpdateChannel.offer(TransitionUpdate.Enter(instant, lastLocation, zone))
            Updates.EXIT -> transitionUpdateChannel.offer(TransitionUpdate.Exit(instant, lastLocation, zone))
            Updates.EXTE -> transitionUpdateChannel.offer(TransitionUpdate.Extension(instant, lastLocation, zone))
        }
    }
}
fun <DateType> LocationModel<DateType>.meetsGeofenceApiRequirements(): Boolean {
    return this.latitude != null && this.longitude != null && this.updatedOn != null && this.floorId != null
}
fun <DateType> ZoneModel<DateType, LocationModel<DateType>, *, *, *>.meetsGeofenceApiRequirements(): Boolean {
    val delimitationsOk = this.delimitations!!.all {
        it.location != null && it.location!!.longitude != null && it.location!!.latitude != null &&
            (it.pattern == DelimitationPattern.CIRCLE.toString() || it.pattern == DelimitationPattern.POLYGON.toString())
    }
    return this.updatedOn != null && this.location != null && this.location!!.meetsGeofenceApiRequirements() && this.delimitations != null && this.delimitations!!.size> 0 && delimitationsOk
}

fun LocationModel<*>.distanceTo(otherLocation: LocationModel<*>): Double {
    val latSq = this.latitude!!.minus(otherLocation.latitude!!).pow(2)
    val lngSq = this.longitude!!.minus(otherLocation.longitude!!).pow(2)
    return latSq.plus(lngSq).pow(0.5)
}
fun <DateType> ZoneModel<DateType, LocationModel<DateType>, *, *, *>.containsLocation(location: LocationModel<DateType>): Boolean {
    val pattern = this.delimitations!!.first().pattern!!
    val sameFloor = location.floorId!! == this.location!!.floorId!!
    return when (pattern) {
        DelimitationPattern.CIRCLE.toString() -> {
            val center = this.location!!
            val circlePoint = this.delimitations!!.first().location!!
            val radius = center.distanceTo(circlePoint)
            val locationDistanceToCenter = location.distanceTo(center)
            locationDistanceToCenter < radius && sameFloor
        }
        DelimitationPattern.POLYGON.toString() -> {
            val lat = location.latitude!!
            val lng = location.longitude!!
            val polygonCoordinatesPoints = arrayOf(
                this.delimitations!!.map {
                    doubleArrayOf(
                        it.location!!.latitude!!, it.location!!.longitude!!
                    )
                }.toTypedArray()
            )
            polygonContains(lat, lng, polygonCoordinatesPoints) && sameFloor
        }
        else -> { false }
    }
}
fun <DateType> getStateListSortedById(state: MutableMap<ZoneModel<DateType, LocationModel<DateType>, *, *, *>, DateType?>)
        :List<Pair<ZoneModel<DateType, LocationModel<DateType>, *, *, *>, DateType?>> {
    val stateList = state.toList()
    return stateList.sortedBy { it.first._id }
}
open class SimpleGeofencingApi<DateType>(inbounds: GeofencingApiInbounds<DateType>,
                                         dateTypeConversion: DateTypeConversion<DateType>) : GeofencingApi<DateType>(inbounds,dateTypeConversion) {



    override var lastTimerInstant: Long = currentTimeInMillis()
    override var lastLocation: LocationModel<DateType> = inbounds.initialLocation // <- TODO must be fantasy floor like -1000 iot work properly
    // state: contains all zones + non null validity if currently entered
    // the instant contains the "changedOn" of the last LocationUpdate
    override var state: MutableMap<ZoneModel<DateType, LocationModel<DateType>, *, *, *>, DateType?> = inbounds.initialTransitions.toMutableMap()

    fun onTimerCollected(instant: DateType) {
        // deprecate all currently entered geofences if timer ticked twice without locationUpdate in between
        val sortedStateList = getStateListSortedById(state)

        sortedStateList.forEach {
            var zone = it.first
            var enteredZoneUpdatedOn = it.second
            if (enteredZoneUpdatedOn != null && dateTypeConversion.toLong(enteredZoneUpdatedOn) < lastTimerInstant) {
                state[zone] = null
                broadcastTransitionUpdate(Updates.DEPR, instant, zone)
            }
        }
        lastTimerInstant = dateTypeConversion.toLong(instant)
    }

    fun onLocationCollected(location: LocationModel<DateType>) {
        lastLocation = location
        val locationUpdatedOn = lastLocation.updatedOn ?: throw IllegalStateException("no time on location")
        val sortedStateList = getStateListSortedById(state)

        sortedStateList.forEach {
            var zone = it.first
            var enteredZoneUpdatedOn = it.second
            // check entered
            if (enteredZoneUpdatedOn == null && zone.containsLocation(lastLocation)) {
                state[zone] = locationUpdatedOn
                broadcastTransitionUpdate(Updates.ENTER, locationUpdatedOn, zone)
            }
            // check exited
            if (enteredZoneUpdatedOn != null && !zone.containsLocation(lastLocation)) {
                state[zone] = null
                broadcastTransitionUpdate(Updates.EXIT, locationUpdatedOn, zone)
            }
            // check extend
            if (enteredZoneUpdatedOn != null && zone.containsLocation(lastLocation)) {
                state[zone] = locationUpdatedOn
                broadcastTransitionUpdate(Updates.EXTE, locationUpdatedOn, zone)
            }
        }
    }

    fun onZoneCollected(zoneUpdate: ZoneModel<DateType, LocationModel<DateType>, *, *, *>, delta: GeofencingApiInbounds.Delta) {
        val lastLocationUpdatedOn = lastLocation.updatedOn ?: throw IllegalStateException("no time on location")
        val zoneUpdateInstant = zoneUpdate.updatedOn ?: throw IllegalStateException("no time on location")
        val sortedStateList = getStateListSortedById(state)


        when (delta) {
            // on delta = delete check if zone should be removed from state
            // if so, create a deprecation event [MO: only if was currently entered]
            GeofencingApiInbounds.Delta.DELETE -> {
                sortedStateList.filter { it.first._id == zoneUpdate._id }.forEach {
                    var stateZone = it.first
                    var stateZoneUpdatedOn = it.second
                    state.remove(stateZone)
                    if (stateZoneUpdatedOn != null) {
                        broadcastTransitionUpdate(Updates.DEPR, zoneUpdateInstant, zoneUpdate)
                    }
                }
            }

            // on insert, take last location and check if inserting zone is entered
            // if yes add to state and broadcast enter event
            GeofencingApiInbounds.Delta.INSERT -> {
                if (zoneUpdate.containsLocation(lastLocation)) {
                    state[zoneUpdate] = lastLocationUpdatedOn
                    broadcastTransitionUpdate(Updates.ENTER, zoneUpdateInstant, zoneUpdate)
                } else {
                    state[zoneUpdate] = null
                }
            }

            // on update (edge case), take last location and check if there is resulting diff
            GeofencingApiInbounds.Delta.UPDATE -> {
                sortedStateList.filter { it.first._id == zoneUpdate._id }.forEach {
                    var stateZone = it.first
                    var stateZoneUpdatedOn = it.second
                    state.remove(stateZone)
                    state[zoneUpdate] = if (zoneUpdate.containsLocation(lastLocation)) { lastLocationUpdatedOn } else { null }
                    if (stateZoneUpdatedOn == null && zoneUpdate.containsLocation(lastLocation)) broadcastTransitionUpdate(
                        Updates.ENTER, zoneUpdateInstant, zoneUpdate)
                    if (stateZoneUpdatedOn != null && zoneUpdate.containsLocation(lastLocation)) broadcastTransitionUpdate(
                        Updates.EXTE, zoneUpdateInstant, zoneUpdate)
                    if (stateZoneUpdatedOn != null && !zoneUpdate.containsLocation(lastLocation)) broadcastTransitionUpdate(
                        Updates.EXIT, zoneUpdateInstant, zoneUpdate)
                }
            }
        }
    }

    // @ExperimentalCoroutinesApi
    @InternalCoroutinesApi
    override suspend fun onApiStart(): Job = coroutineScope{
        launch { inbounds.timerChannel.collect { onTimerCollected(dateTypeConversion.fromLong(it)) } }
        launch { inbounds.locationUpdateChannel.collect { if (it.meetsGeofenceApiRequirements()) onLocationCollected(it) } }
        launch { inbounds.geofenceFlow.collect { if (it.first.meetsGeofenceApiRequirements()) onZoneCollected(it.first, it.second) } }
    }

    override suspend fun onApiClose() {
    } // MIGHT NOT BE NEEDED; TEST THAT

    override suspend fun getEnteredGeofences(): List<ZoneModel<DateType, LocationModel<DateType>, *, *, *>> {
        return state.filter { it.value != null }.keys.toList()
    }

    override suspend fun isGeofenceEntered(zone: ZoneModel<DateType, LocationModel<DateType>, *, *, *>): Boolean {
        return state[zone] != null
    }

    override suspend fun isGeofenceEntered(geofenceId: String): Boolean {
        return state.filter { it.key._id == geofenceId }.all { it.value != null }
    }
}
