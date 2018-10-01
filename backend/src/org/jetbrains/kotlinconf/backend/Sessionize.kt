package org.jetbrains.kotlinconf.backend

import io.ktor.application.*
import io.ktor.client.*
import io.ktor.client.features.json.*
import io.ktor.client.features.json.serializer.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.*
import org.jetbrains.kotlinconf.data.*
import java.time.*
import java.util.concurrent.*

@Volatile
private var sessionizeData: SessionizeData? = null
val comeBackLater = HttpStatusCode(477, "Come Back Later")
val tooLate = HttpStatusCode(478, "Too Late")
val keynoteTimeZone = ZoneId.of("Europe/Paris")!!
val keynoteEndDateTime = ZonedDateTime.of(
    2018, 10, 4, 10, 0, 0, 0, keynoteTimeZone
)!!

const val fakeSessionId = "007"

fun Application.launchSyncJob(sessionizeUrl: String, sessionizeInterval: Long) {
    log.info("Synchronizing each $sessionizeInterval minutes with $sessionizeUrl")
    launch(CommonPool) {
        while (true) {
            log.trace("Synchronizing to Sessionize…")
            synchronizeWithSessionize(sessionizeUrl)
            log.trace("Finished loading data from Sessionize.")
            delay(sessionizeInterval, TimeUnit.MINUTES)
        }
    }
}

private val client = HttpClient {
    install(JsonFeature) {
        serializer = KotlinxSerializer().apply {
            setMapper(AllData::class, AllData.serializer())
        }
    }
}

private fun AllData.fixRoomNames(): AllData {
    var maxId = rooms.maxBy { it.id }!!.id
    var currentRooms = rooms

    val newSessions = sessions.map { session ->
        if (session.startsAt?.startsWith("2018-10-03") != true) return@map session
        val name = session.title
        val (roomNameStart, roomNameEnd) = name.indexOf('[') to name.lastIndexOf(']')

        if (roomNameStart == -1 || roomNameEnd == -1 ||
            roomNameEnd <= roomNameStart + 1 || roomNameStart + 1 >= name.length
        ) return@map session

        val roomName = name.substring(roomNameStart + 1, roomNameEnd)
        val title = name.substring(0, roomNameStart)

        val newRoom = Room(name = roomName, id = maxId, sort = 16)
        maxId++
        currentRooms += newRoom
        return@map session.copy(title = title, roomId = newRoom.id)
    }

    return copy(
        rooms = currentRooms,
        sessions = newSessions
    )
}

suspend fun synchronizeWithSessionize(sessionizeUrl: String) {
    val data = client.get<AllData>(sessionizeUrl).fixRoomNames()
    sessionizeData = SessionizeData(data)
}

fun getSessionizeData() = sessionizeData ?: throw ServiceUnavailable()