package dev.irshadm.volta.core.ismart

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Byte-exact agreement with the Python client (vectors from tools/make_ismart_vectors.py). */
class GoldenVectorTest {
    private val v: JsonObject = Json.parseToJsonElement(javaClass.getResource("/ismart_vectors.json")!!.readText()).jsonObject
    private fun s(k: String) = v[k]!!.jsonPrimitive.content
    private val t = v["time"]!!.jsonPrimitive.long
    private val uid = s("uid")
    private val token = s("token")
    private val vin = s("vin")

    @Test fun statusRequest() = assertEquals(s("statusRequest"), Tap.statusRequest(uid, token, vin, 7, t))
    @Test fun chargeRequest() = assertEquals(s("chargeRequest"), Tap.chargeRequest(uid, token, vin, 0, t))
    @Test fun lockRequest() = assertEquals(s("lockRequest"), Tap.controlRequest(uid, token, vin, 0, 1, emptyList(), t))

    @Test fun unlockRequest() = assertEquals(
        s("unlockRequest"),
        Tap.controlRequest(uid, token, vin, 99, 2, listOf(4 to b(0), 5 to b(0), 6 to b(0), 7 to b(3), 255 to b(0)), t),
    )

    @Test fun climateRequest() = assertEquals(
        s("climateOnRequest"),
        Tap.controlRequest(uid, token, vin, 0, 6, listOf(19 to b(3), 20 to b(3), 255 to b(0)), t),
    )

    @Test fun pinHashAndRequest() {
        assertEquals(s("pinHash"), MgCrypto.pinHash("1234"))
        assertEquals(s("pinRequest"), Tap.pinRequest(uid, token, vin, s("pinHash"), t))
    }

    @Test fun loginBody() {
        val device = s("deviceId")
        assertEquals(s("loginBody"), LoginFrame.build("9876543210", "secret-pass", device, t))
        assertEquals(device, MgCrypto.deviceId("9876543210", "haos-mg-ismart-india-"))
    }

    @Test fun signatures() {
        assertEquals(s("tapSignature"), MgCrypto.tapSignature(s("statusRequest")))
        assertEquals(s("gatewaySignature"), MgCrypto.gatewaySignature("/vehicle/userVinList", "1792979136000"))
    }

    @Test fun gatewayDecrypt() {
        val g = v["gateway"]!!.jsonObject
        assertEquals(g["plain"]!!.jsonPrimitive.content, MgCrypto.decryptGatewayBody(g["cipher"]!!.jsonPrimitive.content, g["timestamp"]!!.jsonPrimitive.content, "application/json"))
    }

    @Test fun statusResponseDecodesToM0Values() {
        val res = Tap.decodeV21(s("statusResponse"))
        assertEquals(4242L, res.dispatcher.eventId)
        assertEquals(vin, res.dispatcher.vin)
        val f = Tap.decodeStatusApp(res.app!!)
        val e = v["statusExpect"]!!.jsonObject
        assertEquals(e["statusTime"]!!.jsonPrimitive.long, f.statusTime)
        assertEquals(e["soc"]!!.jsonPrimitive.int, f.basic.fuelLevelPrc)
        assertEquals(e["mileage"]!!.jsonPrimitive.long, f.basic.mileage)
        assertEquals(e["locked"]!!.jsonPrimitive.boolean, f.basic.locked)
        assertEquals(e["tyreFL"]!!.jsonPrimitive.int, f.basic.tyreFrontLeft)
        assertEquals(e["battery"]!!.jsonPrimitive.int, f.basic.batteryVoltage)
        assertEquals(e["journeyId"]!!.jsonPrimitive.long, f.basic.journeyId)
        assertEquals(e["alerts"]!!.jsonPrimitive.int, f.alerts.size)
        assertEquals(-2, f.position.altitudeM)
        // and the parsed, scaled view
        val st = VehicleSnapshot.fromStatus(f)
        assertEquals(e["soc"]!!.jsonPrimitive.int, st.socPercent)
        assertEquals(e["mileage"]!!.jsonPrimitive.long / 10.0, st.odometerKm!!, 1e-6)
        assertEquals(Math.round(e["tyreFL"]!!.jsonPrimitive.int * 0.2 * 10) / 10.0, st.tyres!!.frontLeft, 1e-9)
        assertEquals(e["battery"]!!.jsonPrimitive.int / 10.0, st.auxVolts!!, 1e-9)
    }

    @Test fun chargeResponseDecodes() {
        val res = Tap.decodeV21(s("chargeResponse"))
        assertEquals(v["chargeAppLen"]!!.jsonPrimitive.int, res.app!!.size)
        val c = ChargeStatus.from(Tap.decodeChargeApp(res.app!!))
        assertEquals(59.0, c.socPercent!!, 1e-9)
        assertEquals(21.9, c.batteryEnergyKwh, 1e-9)
        assertEquals(187.0, c.rangeKm, 1e-9)
        assertEquals(36.6, c.distanceSinceChargeKm!!, 1e-9)
        assertEquals(6.1, c.energySinceChargeKwh!!, 1e-9)
        assertEquals(0.0, c.currentA, 1e-9)
        assertEquals(375.5, c.voltageV, 1e-9)
        assertTrue(!c.charging && !c.pluggedIn)
        assertNull(c.minutesRemaining)
    }

    @Test fun pendingAndSessionResults() {
        val pending = Tap.decodeV21(s("pendingResponse"))
        assertEquals(4, pending.dispatcher.result)
        assertEquals(777L, pending.dispatcher.eventId)
        assertNull(pending.app)
        assertEquals(2, Tap.decodeV21(s("sessionInvalidResponse")).dispatcher.result)
    }

    @Test fun controlAndPinResponses() {
        val c = Tap.decodeControlApp(Tap.decodeV21(s("controlDoneResponse")).app!!)
        assertEquals(1, c.reqType)
        assertEquals(2, c.reqStatus)
        assertEquals(0, Tap.decodePinResponse(s("pinOkResponse")).result)
    }

    @Test fun loginRejectionIsReported() {
        // A V1.1 dispatcher with result 3 and an error message, framed like the login response.
        val d = Tap.encodeDispatcherV11(
            Tap.DispatcherV11(applicationId = "001", eventCreationTime = t, messageId = 2, iccId = "12345678901234567890",
                appDataLength = 0, appProtocolVersion = 513, result = 3, errorMessage = "Incorrect password".toByteArray()),
        )
        val payload = byteArrayOf(17, 0, (d.size + 4).toByte(), 0) + d
        val raw = "%04X".format(payload.size * 2 + 5) + "1" + payload.hexUpper()
        val r = LoginFrame.decode(raw)
        assertIs<LoginFrame.Result.Rejected>(r)
        assertEquals("Incorrect password", r.message)
    }

    private fun b(x: Int) = byteArrayOf(x.toByte())
}
