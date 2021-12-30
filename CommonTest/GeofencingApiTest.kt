package com.tryformation.geofencing

import com.tryformation.clock.InstantDateTypeConversion
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import realm.models.*
import realm.types.DelimitationPattern
import kotlin.math.pow
import kotlin.math.round
import kotlin.test.Test

internal class GeofencingApiTest {
    /*
    private val mainThreadSurrogate = newSingleThreadContext("UI thread") //Executors.newSingleThreadExecutor().asCoroutineDispatcher() //

    @Before
    fun setUp() {
        Dispatchers.setMain(mainThreadSurrogate)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain() // reset main dispatcher to the original Main dispatcher
        mainThreadSurrogate.close()
    }
    */
    @Test
    fun testSortStateById() {
        val inbounds = GeofencingApiInboundsTest()
        val geoApi = SimpleGeofencingApi(inbounds, InstantDateTypeConversion())
        var firstZone = inbounds.zone22poly
        firstZone._id = "1"
        var secondZone = inbounds.zone22poly
        secondZone._id = "2"
        var thirdZone = inbounds.zone22poly
        thirdZone._id = "3"

        val inst = Instant.fromEpochMilliseconds(0)
        geoApi.state = mutableMapOf(thirdZone to inst,
        firstZone to inst,
        secondZone to inst)

        val sorted = getStateListSortedById( geoApi.state)
        val expected = listOf(Pair(firstZone,inst),Pair(secondZone,inst),Pair(thirdZone,inst))
        sorted shouldBe expected
    }
    @ExperimentalCoroutinesApi
    @InternalCoroutinesApi
    @Test
    fun testMeetsGeofenceApiRequirements() {
        val inbounds = GeofencingApiInboundsTest()

        //region test LocationModel.meetsGeofenceApiRequirements()
        val loc22 = inbounds.loc22

        val loc22latNull = loc22.clone()
        val loc22lngNull = loc22.clone()
        val loc22updatedOnNull = loc22.clone()
        val loc22floorIdNull = loc22.clone()

        loc22latNull.latitude = null
        loc22lngNull.longitude = null
        loc22floorIdNull.floorId = null
        loc22updatedOnNull.updatedOn = null

        loc22.meetsGeofenceApiRequirements() shouldBe true
        loc22latNull.meetsGeofenceApiRequirements() shouldBe false
        loc22lngNull.meetsGeofenceApiRequirements() shouldBe false
        loc22updatedOnNull.meetsGeofenceApiRequirements() shouldBe false
        loc22floorIdNull.meetsGeofenceApiRequirements() shouldBe false
        //endregion
        //region test ZoneModel.meetsGeofenceApiRequirements()
        val zone22poly = inbounds.zone22poly
        val zone22antipattern = inbounds.zone22antipattern
        zone22poly.meetsGeofenceApiRequirements() shouldBe true
        zone22antipattern.meetsGeofenceApiRequirements() shouldBe false
        //endregion
    }
    @Test
    fun testLocationModelDistanceTo() {
        val inbounds = GeofencingApiInboundsTest()

        inbounds.loc22.distanceTo(inbounds.loc24) shouldBe (2).toDouble()
        inbounds.loc22.distanceTo(inbounds.loc44) shouldBe (4 + 4).toDouble().pow(0.5)
    }
    @Test
    fun testZoneModelContainsLocation() {
        val inbounds = GeofencingApiInboundsTest()

        //region test for circle pattern
        inbounds.zone4Acirc.containsLocation(inbounds.loc42) shouldBe true
        inbounds.zone4Acirc.containsLocation(inbounds.loc24) shouldBe false
        //endregion
        //region test for polygon pattern
        inbounds.zone22poly.containsLocation(inbounds.loc22) shouldBe true
        inbounds.zone22poly.containsLocation(inbounds.loc24) shouldBe false
        inbounds.zone2Apoly.containsLocation(inbounds.loc22) shouldBe true
        inbounds.zone2Apoly.containsLocation(inbounds.loc24) shouldBe true
        inbounds.zone2Apoly.containsLocation(inbounds.loc42) shouldBe false
        inbounds.zone2Apoly.containsLocation(inbounds.diffFloorLoc24) shouldBe false
        //endregion
    }
    @ExperimentalCoroutinesApi
    @InternalCoroutinesApi
    @Test
    fun testOnTimerCollected() = runTest {
        var cancel = false

        //region input definitions
        val inbounds = GeofencingApiInboundsTest()
        val geoApi = SimpleGeofencingApi(inbounds, InstantDateTypeConversion())

        val loc22 = inbounds.loc22.clone()
        loc22.updatedOn = Instant.fromEpochMilliseconds(0)

        geoApi.lastTimerInstant = 0
        geoApi.lastLocation = loc22
        geoApi.state = mutableMapOf(inbounds.zone22poly to Instant.fromEpochMilliseconds(0))

        //endregion

        val scope = GlobalScope
        //region collect locations one by one and check state modifications
        likeLaunch(block = {
            geoApi.state[inbounds.zone22poly] shouldBe Instant.fromEpochMilliseconds(0)

            geoApi.onTimerCollected(Instant.fromEpochMilliseconds(1))
            geoApi.state[inbounds.zone22poly] shouldBe Instant.fromEpochMilliseconds(0)

            geoApi.onTimerCollected(Instant.fromEpochMilliseconds(2))
            geoApi.state[inbounds.zone22poly] shouldBe null
        },scope= scope)
        //endregion

        //region receive updates and check their validity
        likeLaunch(block = {
            geoApi.transitionUpdateChannel.receiveAsFlow().buffer().collect {
                geoApi.state[inbounds.zone22poly] shouldBe null
                it.instant shouldBe Instant.fromEpochMilliseconds(2)
                it.geofence._id shouldBe "a_zone22poly"
                it.location shouldBe loc22
                it.updateType shouldBe Updates.DEPR
                cancel = true
            }
        },scope=scope)
        //endregion

        while (!cancel) { delay(100) }
    }
    @ExperimentalCoroutinesApi
    @InternalCoroutinesApi
    @Test
    fun testOnLocationCollected() = runTest {
        var cancel = false

        //region input definitions
        val inbounds = GeofencingApiInboundsTest()
        val geoApi = SimpleGeofencingApi(inbounds, InstantDateTypeConversion())

        geoApi.state = mutableMapOf(
            inbounds.zone22poly to null,
            inbounds.zone24poly to null
        )

        val loc22 = inbounds.loc22.clone()
        val loc24a = inbounds.loc24.clone()
        val loc24b = inbounds.loc24.clone()

        loc22.updatedOn = Instant.fromEpochMilliseconds(1)
        loc24a.updatedOn = Instant.fromEpochMilliseconds(2)
        loc24b.updatedOn = Instant.fromEpochMilliseconds(3)

        val instants = listOf(
            Instant.fromEpochMilliseconds(1),
            Instant.fromEpochMilliseconds(2), Instant.fromEpochMilliseconds(2),
            Instant.fromEpochMilliseconds(3)
        )
        val zoneIds = listOf("a_zone22poly", "a_zone22poly", "c_zone24poly", "c_zone24poly")
        val updateTypes = listOf(Updates.ENTER, Updates.EXIT, Updates.ENTER, Updates.EXTE)

        val locList = listOf<LocationModel<Instant>>(loc22, loc24a, loc24a, loc24b)
        //endregion

        //region collect locations one by one and check state modifications
        val scope = GlobalScope
        likeLaunch(block = {
            geoApi.state.forEach { it.value shouldBe null }

            geoApi.onLocationCollected(loc22)
            geoApi.lastLocation = loc22
            geoApi.state[inbounds.zone22poly] shouldBe Instant.fromEpochMilliseconds(1)
            geoApi.state[inbounds.zone24poly] shouldBe null

            geoApi.onLocationCollected(loc24a)
            geoApi.lastLocation = loc24a
            geoApi.state[inbounds.zone22poly] shouldBe null
            geoApi.state[inbounds.zone24poly] shouldBe Instant.fromEpochMilliseconds(2)

            geoApi.onLocationCollected(loc24b)
            geoApi.lastLocation = loc24b
            geoApi.state[inbounds.zone22poly] shouldBe null
            geoApi.state[inbounds.zone24poly] shouldBe Instant.fromEpochMilliseconds(3)
        },scope = scope)
        //endregion

        //region receive updates and check their validity
        likeLaunch(block = {
            var index = 0
            geoApi.transitionUpdateChannel.receiveAsFlow().buffer().collect {
                it.instant shouldBe instants[index]
                it.geofence._id shouldBe zoneIds[index]
                it.location shouldBe locList[index]
                it.updateType shouldBe updateTypes[index]
                index += 1
                if (index == 4) cancel = true
            }
        },scope=scope)
        //endregion

        while (!cancel) { delay(100) }
    }
    @ExperimentalCoroutinesApi
    @InternalCoroutinesApi
    @Test
    fun testOnZoneCollected() = runTest {
        var cancel = false

        //region input definitions
        val inbounds = GeofencingApiInboundsTest()
        val geoApi = SimpleGeofencingApi(inbounds, InstantDateTypeConversion())

        geoApi.lastLocation = inbounds.loc22

        val zone22polyModified1 = inbounds.zone22poly.clone()
        val zone22polyModified2 = inbounds.zone22poly.clone()
        val zone2ApolyModified = inbounds.zone2Apoly.clone()

        zone22polyModified1.updatedOn = Instant.fromEpochMilliseconds(1)
        zone22polyModified2.updatedOn = Instant.fromEpochMilliseconds(2)
        zone2ApolyModified.updatedOn = Instant.fromEpochMilliseconds(3)

        val instants = listOf(Instant.fromEpochMilliseconds(1), Instant.fromEpochMilliseconds(2), Instant.fromEpochMilliseconds(2), Instant.fromEpochMilliseconds(3))
        val zoneIds = listOf("a_zone22poly", "a_zone22poly", "a_zone22poly", "d_zone2Apoly")
        val updateTypes = listOf(Updates.ENTER, Updates.EXTE, Updates.DEPR, Updates.ENTER)
        //endregion

        //region collect zones one by one and check state modifications
        val scope = GlobalScope
        likeLaunch(block = {
            geoApi.state.filter { it.key._id == "a_zone22poly" }.size shouldBe 0
            geoApi.onZoneCollected(zone22polyModified1, GeofencingApiInbounds.Delta.INSERT)
            geoApi.state.filter { it.key._id == "a_zone22poly" }.size shouldBe 1
            geoApi.onZoneCollected(zone22polyModified2, GeofencingApiInbounds.Delta.UPDATE)
            geoApi.state.filter { it.key._id == "a_zone22poly" }.size shouldBe 1
            geoApi.onZoneCollected(zone22polyModified2, GeofencingApiInbounds.Delta.DELETE)
            geoApi.state.filter { it.key._id == "a_zone22poly" }.size shouldBe 0
            geoApi.onZoneCollected(zone2ApolyModified, GeofencingApiInbounds.Delta.INSERT)
            geoApi.state.filter { it.key._id == "d_zone2Apoly" }.size shouldBe 1
        },scope=scope)
        //endregion

        //region receive updates and check their validity
        likeLaunch(block = {
            var index = 0
            geoApi.transitionUpdateChannel.receiveAsFlow().buffer().collect {
                it.instant shouldBe instants[index]
                it.geofence._id shouldBe zoneIds[index]
                it.updateType shouldBe updateTypes[index]
                it.location shouldBe inbounds.loc22
                index += 1
                if (index == 4) cancel = true
            }
        },scope=scope)
        //endregion

        while (!cancel) { delay(100) }

    }
    @ExperimentalCoroutinesApi
    @InternalCoroutinesApi
    @Test
    fun testOnApiStartComplex() = run {
        for (i in 1..20) {
        runTest {
            var cancel = false

            //region input definitions
            val inbounds = GeofencingApiInboundsTest()
            val geoApi = SimpleGeofencingApi(inbounds, InstantDateTypeConversion())
            geoApi.lastTimerInstant = -1

            fun inst(int: Int): Instant = Instant.fromEpochMilliseconds(int.toLong())
            val transitionUpdateList = listOf(
                    TransitionUpdate.Enter(inst(70), inbounds.loc22d, inbounds.zone22polyd),
                    TransitionUpdate.Enter(inst(70), inbounds.loc22d, inbounds.zoneApoly),
                    TransitionUpdate.Exit(inst(140), inbounds.loc24d, inbounds.zone22polyd),
                    TransitionUpdate.Extension(inst(140), inbounds.loc24d, inbounds.zoneApoly),
                    TransitionUpdate.Enter(inst(180), inbounds.loc24d, inbounds.zone24polyd), // 5>4
                    TransitionUpdate.Deprecation(inst(180), inbounds.loc24d, inbounds.zone24polyd),
                    TransitionUpdate.Enter(inst(210), inbounds.loc44d, inbounds.zone4Acircd),
                    TransitionUpdate.Extension(inst(210), inbounds.loc44d, inbounds.zoneApoly),
                    TransitionUpdate.Deprecation(inst(400), inbounds.loc44d, inbounds.zone4Acircd),
                    TransitionUpdate.Deprecation(inst(400), inbounds.loc44d, inbounds.zoneApoly),
                    TransitionUpdate.Enter(inst(480), inbounds.loc42d, inbounds.zone4Acircd),
                    TransitionUpdate.Enter(inst(480), inbounds.loc42d, inbounds.zoneApoly),
                    TransitionUpdate.Exit(inst(550), inbounds.diffFloorLoc24d, inbounds.zone4Acircd),
                    TransitionUpdate.Exit(inst(550), inbounds.diffFloorLoc24d, inbounds.zoneApoly)

            )
            //endregion
            val scope = GlobalScope
            likeLaunch(block = { geoApi.onApiStart() }, scope = scope)
            likeLaunch(block = {
                var index = 0
                geoApi.transitionUpdateChannel.receiveAsFlow().buffer(UNLIMITED).collect {
                    println("received")
                    println(it.location.latitude.toString() + " " + it.location.longitude.toString() + " " + it.updateType.toString() + " " + it.instant.toString() + " " + it.geofence._id)
                    println("expected")
                    println(transitionUpdateList[index].location.latitude.toString() + " " + transitionUpdateList[index].location.longitude.toString() + " " + transitionUpdateList[index].updateType.toString() + " " + transitionUpdateList[index].instant.toString() + " " + transitionUpdateList[index].geofence._id)
                    it.location shouldBe transitionUpdateList[index].location
                    it.updateType shouldBe transitionUpdateList[index].updateType
                    it.instant shouldBe transitionUpdateList[index].instant
                    it.geofence shouldBe transitionUpdateList[index].geofence
                    index += 1
                    if (index == transitionUpdateList.size) cancel = true
                }
            }, scope = scope)
            while (!cancel) {
                delay(100)
            }
            //
        }
    }
    }
    @ExperimentalCoroutinesApi
    @InternalCoroutinesApi
    @Test
    fun testOnApiStartLocAndZoneUpdateVeryShortlyAfterEachOther() = runTest{ /*run {
        for (i in 1..20) {
            runTest {*/
                // confusing, sometimes passes sometimes doenst on jvm
                var cancel = false

                //region input definitions
                val del11 = DelimitationModelTest(LocationModelTest("1", 1.0, 1.0), DelimitationPattern.POLYGON.toString())
                val del13 = DelimitationModelTest(LocationModelTest("1", 1.0, 3.0), DelimitationPattern.POLYGON.toString())
                val del31 = DelimitationModelTest(LocationModelTest("1", 3.0, 1.0), DelimitationPattern.POLYGON.toString())
                val del33 = DelimitationModelTest(LocationModelTest("1", 3.0, 3.0), DelimitationPattern.POLYGON.toString())

                val loc1 = LocationModelTest("1", 2.0, 2.0).withEpochMilli(1)
                val zone22poly = ZoneModelTest("zone22poly", loc1, listOf(del11, del31, del33, del13)).withEpochMilli(10)
                val loc2 = LocationModelTest("1", 2.0, 2.1).withEpochMilli(19)

                val locationUpdateChannel = listOf(loc1, loc2).asFlow().onEach {
                    println("locUpdate"); if (it == loc1) delay(1) else {
                    delay(9)
                }
                }
                val geofenceFlow = listOf(Pair(zone22poly, GeofencingApiInbounds.Delta.INSERT)).asFlow().onEach { println("geoUpdate");delay(10) }

                val inbounds = GeofencingApiInboundsTest(
                        locationUpdateChannel = locationUpdateChannel,
                        geofenceFlow = geofenceFlow
                )
                val geoApi = SimpleGeofencingApi(inbounds, InstantDateTypeConversion())
                geoApi.lastTimerInstant = -1
                geoApi.state = mutableMapOf()

                fun inst(int: Int): Instant = Instant.fromEpochMilliseconds(int.toLong())
                val transitionUpdateList = listOf(
                        TransitionUpdate.Enter(inst(10), loc1, zone22poly),
                        TransitionUpdate.Extension(inst(19), loc2, zone22poly)
                )
                //endregion
                val scope = GlobalScope
                likeLaunch(block = { geoApi.onApiStart() }, scope = scope)
                likeLaunch(block = {
                    var index = 0
                    geoApi.transitionUpdateChannel.receiveAsFlow().buffer().collect {
                        println("INDEXXXXXX")
                        println(index)
                        println("received")
                        println(it.location.updatedOn)
                        println("should be")
                        println(transitionUpdateList[index].location.updatedOn)
                        if (index<2) {
                            it.location shouldBe transitionUpdateList[index].location
                            it.updateType shouldBe transitionUpdateList[index].updateType
                            it.instant shouldBe transitionUpdateList[index].instant
                            it.geofence shouldBe transitionUpdateList[index].geofence
                            index += 1
                            if (index == transitionUpdateList.size) cancel = true
                        }
                    }
                }, scope = scope)
                while (!cancel) {
                    delay(10)
                }

            }
        /*}
    }*/
    @Test
    fun testGetEnteredGeofencesAndIsGeofenceEntered() = runTest {
        var cancel = false
        val inbounds = GeofencingApiInboundsTest()
        val geoApi = SimpleGeofencingApi(inbounds, InstantDateTypeConversion())

        geoApi.state = mutableMapOf(
                inbounds.zone22poly to null,
                inbounds.zone24poly to Instant.fromEpochMilliseconds(1),
                inbounds.zoneApoly to Instant.fromEpochMilliseconds(1)
        )

        val scope = GlobalScope
        likeLaunch(block={
            println(inbounds.zone24poly._id)
            geoApi.getEnteredGeofences() shouldBe listOf(inbounds.zone24poly, inbounds.zoneApoly)
            geoApi.isGeofenceEntered("c_zone24poly") shouldBe true
            geoApi.isGeofenceEntered("a_zone22poly") shouldBe false
            println("here")
            geoApi.isGeofenceEntered(inbounds.zone24poly) shouldBe true
            geoApi.isGeofenceEntered(inbounds.zone22poly) shouldBe false
            cancel = true
        },scope=scope)
        while (!cancel) delay(100)

    }
}


class LocationModelTest(floorId: String, lat: Double, lng: Double, updatedOn: Instant? = null) : LocationModel<Instant> {
    override var access: String? = null
    override var accuracy: Double? = null
    override var altitude: Double? = null
    override var createdBy: String? = null
    override var floorId: String? = floorId
    override var _id: String? = null
    override var latitude: Double? = lat
    override var level: Int? = null
    override var longitude: Double? = lng
    override var orientation: Double? = null
    override var ownerId: String? = null
    override var ownerKind: String? = null
    override var source: String? = null
    override var type: String? = null
    override var updates: Int? = null
    override var userId: String? = null
    override var validity: Instant? = null
    override var venueId: String? = null
    override val bearing: Double? = null
    override val bearingAccuracyDegrees: Double? = null
    override var createdOn: Instant? = null
    override val floorCertainty: Double? = null
    override val speed: Double? = null
    override val speedAccuracyMetersPerSecond: Double? = null
    override var updatedBy: String? = null
    override var updatedOn: Instant? = updatedOn ?: Clock.System.now()
    override val verticalAccuracy: Double? = null

    fun withEpochMilli(int: Int): LocationModel<Instant> {
        this.updatedOn = Instant.fromEpochMilliseconds(int.toLong())
        return this
    }

    fun clone() = LocationModelTest(floorId!!, latitude!!, longitude!!)
}
class DelimitationModelTest(location: LocationModel<Instant>, pattern: String) : DelimitationModel<Instant, LocationModel<Instant>> {
    override var updatedBy: String? = null
    override var updatedOn: Instant? = null
    override var createdOn: Instant? = null
    override var createdBy: String? = null
    override var _id: String? = null
    override var location: LocationModel<Instant>? = location
    override var ownerId: String? = null
    override var ownerKind: String? = null
    override var pattern: String? = pattern
    override var size: Int? = null
    override var type: String? = null
}
class ZoneModelTest(
    id: String,
    location: LocationModel<Instant>,
    override var delimitations: List<DelimitationModel<Instant, LocationModel<Instant>>>,
    updatedOn: Instant? = null
) :
    ZoneModel<Instant, LocationModel<Instant>, DelimitationModel<Instant, *>, MarkerModel<Instant>, RecordModel<*>> {
    override var latitude: Double? = null
    override var longitude: Double? = null
    override var type: String? = null
    override var updatedBy: String? = null
    override var updatedOn: Instant? = updatedOn ?: Clock.System.now()
    override var createdOn: Instant? = null
    override var createdBy: String? = null
    override var _id: String? = id
    override var label: String? = null
    override var location: LocationModel<Instant>? = location
    override var marker: MarkerModel<Instant>? = null
    override var record: RecordModel<*>? = null

    fun withEpochMilli(int: Int): ZoneModel<Instant, LocationModel<Instant>, DelimitationModel<Instant, *>, MarkerModel<Instant>, RecordModel<*>> {
        this.updatedOn = Instant.fromEpochMilliseconds(int.toLong())
        return this
    }

    fun clone() = ZoneModelTest(_id!!, location!!, delimitations)
}

@Suppress("MemberVisibilityCanBePrivate")
class GeofencingApiInboundsTest(
    timerChannel: Flow<Long>? = null,
    locationUpdateChannel: Flow<LocationModel<Instant>>? = null,
    geofenceFlow: Flow<Pair<    
            ZoneModel<Instant, LocationModel<Instant>, DelimitationModel<Instant, *>, MarkerModel<Instant>, RecordModel<*>>,
            GeofencingApiInbounds.Delta
            >>? = null
) : GeofencingApiInbounds<Instant> {

    //region definitions
    val loc22 = LocationModelTest("1", 2.0, 2.0)
    val loc24 = LocationModelTest("1", 2.0, 4.0)
    val loc44 = LocationModelTest("1", 4.0, 4.0)
    val loc42 = LocationModelTest("1", 4.0, 2.0)
    val diffFloorLoc24 = LocationModelTest("2", 2.0, 4.0)
    val loc53 = LocationModelTest("1", 5.0, 3.0)

    val loc22d = loc22.withEpochMilli(70)
    val loc24d = loc24.withEpochMilli(140)
    val loc44d = loc44.withEpochMilli(210)
    val loc42d = loc42.withEpochMilli(480)
    val diffFloorLoc24d = diffFloorLoc24.withEpochMilli(550)

    val del11 = DelimitationModelTest(LocationModelTest("1", 1.0, 1.0), DelimitationPattern.POLYGON.toString())
    val del11antipattern = DelimitationModelTest(LocationModelTest("1", 1.0, 1.0), "antipattern")
    val del13 = DelimitationModelTest(LocationModelTest("1", 1.0, 3.0), DelimitationPattern.POLYGON.toString())
    val del15 = DelimitationModelTest(LocationModelTest("1", 1.0, 5.0), DelimitationPattern.POLYGON.toString())
    val del31 = DelimitationModelTest(LocationModelTest("1", 3.0, 1.0), DelimitationPattern.POLYGON.toString())
    val del33 = DelimitationModelTest(LocationModelTest("1", 3.0, 3.0), DelimitationPattern.POLYGON.toString())
    // val del35 = DelimitationModelTest(LocationModelTest("1",3.0, 5.0), DelimitationPattern.POLYGON.toString())
    val del51 = DelimitationModelTest(LocationModelTest("1", 5.0, 1.0), DelimitationPattern.POLYGON.toString())
    val del53 = DelimitationModelTest(LocationModelTest("1", 5.0, 3.0), DelimitationPattern.POLYGON.toString())
    val del55 = DelimitationModelTest(LocationModelTest("1", 5.0, 5.0), DelimitationPattern.POLYGON.toString())

    val del55c = DelimitationModelTest(LocationModelTest("1", 5.0, 5.0), DelimitationPattern.CIRCLE.toString())

    val zone22poly = ZoneModelTest("a_zone22poly", loc22, listOf(del11, del31, del33, del13))
    val zone22antipattern = ZoneModelTest("b_zone22poly", loc22, listOf(del11antipattern, del31, del33, del13))
    val zone24poly = ZoneModelTest("c_zone24poly", loc24, listOf(del31, del51, del53, del33))
    val zone2Apoly = ZoneModelTest("d_zone2Apoly", loc22, listOf(del11, del51, del53, del13))
    val zone4Acirc = ZoneModelTest("e_zone4Acirc", loc53, listOf(del55c))
    val zoneApoly = ZoneModelTest("f_zoneApoly", loc22, listOf(del11, del51, del55, del15))

    val zone22polyd = zone22poly.withEpochMilli(1)
    val zone4Acircd = zone4Acirc.withEpochMilli(1)
    val zone24polyd = zone24poly.withEpochMilli(180)

    val delete = GeofencingApiInbounds.Delta.DELETE
    val insert = GeofencingApiInbounds.Delta.INSERT
    //endregion
    var startTime: Double? = null
    fun getMillisPassed() = if (startTime == null) {
        startTime = Clock.System.now().toEpochMilliseconds().toDouble()
        (0).toDouble()
    } else {
        round(Clock.System.now().toEpochMilliseconds().toDouble().minus(startTime!!.toDouble()))
    }
    fun message(whichFlow: String, whatPrinted: String, updatedOn: Instant? = null) {
        var string = "${getMillisPassed()} $whichFlow: $whatPrinted"
        string = if (updatedOn == null) { string } else { string + ", updatedOn: ${updatedOn.toEpochMilliseconds()}" }
        println(string)
    }

    val timerList = listOf<Long>(100, 200, 300, 400, 500, 600)
    val locList = listOf(loc22d, loc24d, loc44d, loc42d, diffFloorLoc24d)
    val zoneDeltaList = listOf(
        Pair(zone22polyd, insert),
        Pair(zone4Acircd, insert),
        Pair(zone24polyd, insert),
        Pair(zone24polyd, delete)
    )

    override val initialLocation = LocationModelTest("-100", (0).toDouble(), (0).toDouble())
    override val initialTransitions: Map<ZoneModel<Instant, LocationModel<Instant>, *, *, *>, Instant?> = mutableMapOf(zoneApoly to null)

    override val timerChannel = timerChannel
        ?: timerList.asFlow().onEach { delay(100); message("timer", it.toString()) }

    override val locationUpdateChannel = locationUpdateChannel
        ?: locList.asFlow().onEach { if (it == loc42d) delay(270) else { delay(70) }; message("loc", it.latitude.toString() + " " + it.longitude.toString(), it.updatedOn) }

    override val geofenceFlow = geofenceFlow
        ?: zoneDeltaList.asFlow().onEach { if (it == Pair(zone24polyd, insert)) delay(180) ; message("zone", it.first._id.toString(), it.first.updatedOn) }
}
