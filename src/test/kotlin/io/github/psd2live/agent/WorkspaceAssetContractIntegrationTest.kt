package io.github.psd2live.agent

import io.github.psd2live.application.*
import io.github.psd2live.ui.state.*
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.nio.file.Path
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.*

class WorkspaceAssetContractIntegrationTest {
    private data class AssetCall(val data: JsonObject, val images: List<ImageContent>)
    @TempDir lateinit var temporary: Path
    private val connection = java.lang.reflect.Proxy.newProxyInstance(ClientConnection::class.java.classLoader,
        arrayOf(ClientConnection::class.java)) { _, method, _ ->
        if (method.name == "getSessionId") "asset-contract" else error("Unexpected client notification")
    } as ClientConnection

    @Test fun desktopAndMcpReturnStoredReferencesRegistrationsAndOriginalPngsThroughExactContracts() = runBlocking<Unit> {
        val source = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
        val generated = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
        for (y in 0..15) for (x in 0..15) {
            source.setRGB(x, y, 0xff526080.toInt())
            if (x in 3..12 && y in 3..12) generated.setRGB(x, y, 0xffff3366.toInt())
        }
        val sourcePath = temporary.resolve("source.png"); val generatedPath = temporary.resolve("generated.png")
        ImageIO.write(source, "png", sourcePath.toFile()); ImageIO.write(generated, "png", generatedPath.toFile())
        PSD2LiveViewModel().use { vm ->
            vm.setStateForTest(vm.state.value.copy(atlasSize = 256, meshOnly = true, generatePhysics = false))
            DesktopWorkspace(vm, temporary.resolve("store")).use { workspace ->
                vm.attachWorkspace(workspace)
                workspace.createArtwork(buildJsonObject {
                    put("width", 32); put("height", 32)
                    putJsonArray("layers") { add(buildJsonObject { put("path", sourcePath.toString()); put("name", "Artwork"); put("role", "objects") }) }
                })
                WorkspaceOperations(workspace).use { operations ->
                    val server = createAgentMcpServer(workspace, operations = operations)
                    var requestNumber = 0
                    suspend fun call(id: String, business: JsonObject): AssetCall {
                        val definition = operations.registry.definition(id)
                        val request = if (definition.kind == WorkspaceOperationKind.QUERY) business else buildJsonObject {
                            val snapshot = workspace.snapshot()
                            put("request_id", "asset-${requestNumber++}"); put("project_id", snapshot.projectId); put("state", snapshot.state)
                            business.forEach { (key, value) -> put(key, value) }
                        }
                        val result = server.tools.getValue(id).handler.invoke(connection,
                            CallToolRequest(CallToolRequestParams(id, buildJsonObject { put("request", request) })))
                        assertFalse(result.isError == true, result.structuredContent.toString())
                        validateOperationSchema(result.structuredContent!!, definition.responseEnvelope())
                        var actual = result
                        var data = actual.structuredContent!!.getValue("data").jsonObject
                        if (definition.jobBacked) {
                            val job = data
                            actual = server.tools.getValue("job_wait").handler.invoke(connection,
                                CallToolRequest(CallToolRequestParams("job_wait", buildJsonObject {
                                    putJsonObject("request") { put("id", job.getValue("id")) }
                                })))
                            assertFalse(actual.isError == true, actual.structuredContent.toString())
                            validateOperationSchema(actual.structuredContent!!, operations.registry.definition("job_wait").responseEnvelope())
                            val terminal = actual.structuredContent!!.getValue("data").jsonObject
                            assertEquals("completed", terminal.getValue("status").jsonPrimitive.content, terminal.toString())
                            data = terminal.getValue("result").jsonObject
                            validateOperationSchema(data, definition.jobResultSchema!!)
                            assertEquals(workspace.snapshot().state, data.getValue("state").jsonPrimitive.content)
                            val retried = server.tools.getValue(id).handler.invoke(connection,
                                CallToolRequest(CallToolRequestParams(id, buildJsonObject { put("request", request) })))
                            assertEquals(job.getValue("id"), retried.structuredContent!!.getValue("data").jsonObject.getValue("id"))
                        }
                        return AssetCall(data, actual.content.filterIsInstance<ImageContent>())
                    }
                    val layer = workspace.snapshot().layers.first { !it.deleted }.id
                    val history = workspace.history()
                    val referenceResult = call("asset_prepare_reference", buildJsonObject {
                        put("layer_id", layer); put("piece_id", "decoration"); put("background_color", "#11ccff"); put("target_long_edge", 128)
                        putJsonObject("target_anchors") {
                            putJsonObject("root") { put("x", 0); put("y", 0) }
                            putJsonObject("tip") { put("x", 16); put("y", 16) }
                        }
                    })
                    val reference = referenceResult.data.getValue("id")
                    assertEquals(2, referenceResult.images.size)
                    val importDefinition = operations.registry.definition("asset_import_png")
                    val importProperties = importDefinition.requestSchema.getValue("properties").jsonObject
                    assertTrue("png_path" in importProperties)
                    assertTrue("png_base64" in importProperties)
                    assertFalse(importDefinition.requestSchema.getValue("required").jsonArray.any { it.jsonPrimitive.content == "png_path" })

                    val imported = call("asset_import_png", buildJsonObject {
                        put("png_path", generatedPath.toString()); put("reference_id", reference); put("require_transparency", true)
                    }).data
                    val encoded = Base64.getEncoder().encodeToString(generatedPath.toFile().readBytes())
                    val importedInline = call("asset_import_png", buildJsonObject {
                        put("png_base64", "data:image/png;base64,$encoded"); put("reference_id", reference); put("require_transparency", true)
                    }).data
                    assertEquals(imported.getValue("assetId"), importedInline.getValue("assetId"))
                    val assetId = imported.getValue("assetId")
                    val registration = call("asset_register", buildJsonObject { put("asset_id", assetId); put("mode", "frame") })
                        .data
                    val inspected = call("asset_inspect", buildJsonObject { put("asset_id", assetId) })
                    assertEquals(2, inspected.images.size)
                    val details = inspected.data.getValue("asset").jsonObject.getValue("details").jsonObject
                    assertEquals(reference, details.getValue("reference").jsonObject.getValue("id"))
                    assertEquals(JsonArray(listOf(JsonObject(registration - setOf("state", "history_node_id")))), details.getValue("registrations"))
                    assertTrue("orientation_diagnostic" in details)
                    val composite = call("asset_preview_composite", buildJsonObject {
                        putJsonArray("placements") { add(buildJsonObject { put("registration_id", registration.getValue("id")) }) }
                        put("replace_layer_ids", JsonArray(listOf(JsonPrimitive(layer)))); put("target_long_edge", 128)
                    })
                    assertEquals(1, composite.images.size)
                    val reprocessed = call("asset_reprocess", buildJsonObject { put("asset_id", assetId); putJsonObject("processing") { put("edge_width", 0) } })
                        .data
                    assertNotEquals(assetId, reprocessed.getValue("asset_id"))
                    assertEquals(history, workspace.history(), "Staged assets and inspection do not create Rig history")
                    assertEquals(imported.getValue("details"), workspace.inspectAsset(assetId.jsonPrimitive.content).asset.details.let {
                        JsonObject(it - setOf("reference", "registrations", "orientation_diagnostic")) })
                    val savedState = workspace.snapshot().state
                    val archive = temporary.resolve("assets.psd2live")
                    call("project_save_as", buildJsonObject { put("path", archive.toString()) })
                    call("project_open", buildJsonObject { put("path", archive.toString()) })
                    assertNotEquals(savedState, workspace.snapshot().state)
                    val reopened = call("asset_inspect", buildJsonObject { put("asset_id", assetId) })
                    assertEquals(inspected.data, reopened.data)
                    inspected.images.zip(reopened.images).forEach { (before, after) -> assertEquals(before.data, after.data) }
                    assertEquals(history, workspace.history())
                }
            }
        }
    }
}
