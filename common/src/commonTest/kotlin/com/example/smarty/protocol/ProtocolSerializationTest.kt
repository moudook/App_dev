package com.example.smarty.protocol

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class ProtocolSerializationTest {

    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
    }

    @Test
    fun testLaunchAppCommandSerialization() {
        val cmd: AgentCommand = AgentCommand.LaunchApp(
            commandId = "cmd_123",
            packageName = "com.spotify.music"
        )
        val string = json.encodeToString(AgentCommand.serializer(), cmd)

        // Debug output
        println("Encoded JSON: $string")

        val decoded = json.decodeFromString<AgentCommand>(string)
        assertTrue(decoded is AgentCommand.LaunchApp)
        assertEquals("cmd_123", decoded.commandId)
        assertEquals("com.spotify.music", decoded.packageName)
    }

    @Test
    fun testAppStateEventSerialization() {
        val event: ClientEvent = ClientEvent.AppState(
            currentScreen = "Home",
            batteryLevel = 0.85f,
            isWifi = true,
            timestamp = 1000L
        )
        val string = json.encodeToString(ClientEvent.serializer(), event)

        // Debug output
        println("Encoded JSON: $string")

        val decoded = json.decodeFromString<ClientEvent>(string)
        assertTrue(decoded is ClientEvent.AppState)
        assertEquals("Home", decoded.currentScreen)
        assertEquals(0.85f, decoded.batteryLevel)
    }
}
