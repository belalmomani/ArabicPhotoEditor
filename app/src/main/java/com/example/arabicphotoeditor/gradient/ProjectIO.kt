package com.example.arabicphotoeditor.gradient

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.InflaterInputStream

/**
 * Project file format: plain JSON (human-readable, versioned), so a project can always be reopened and edited.
 * Colors are stored as "#AARRGGBB". A gradient-map photo is stored as deflate-compressed 8-bit luminance (base64),
 * so the project is a single self-contained file. Older versions (1 and 2) still open.
 */
object ProjectIO {

    const val FORMAT = "gradient-studio"
    const val VERSION = 3

    private const val MAX_LAYERS = 64
    private const val MAX_PATH_POINTS = 256
    private const val MAX_GRAVITY_POINTS = 16
    private const val MAX_LIGHTS = 8
    private const val MAX_MAP_SIDE = 2048

    private fun hex(color: Int) = String.format("#%08X", color)

    private fun parseHex(text: String, fallback: Int): Int = try {
        java.lang.Long.parseLong(text.removePrefix("#"), 16).toInt()
    } catch (e: NumberFormatException) {
        fallback
    }

    // ------------------------------------------------------------ writing

    fun toJson(project: GradientProject): String {
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("version", VERSION)
        root.put("width", project.width)
        root.put("height", project.height)
        root.put("background", hex(project.background))
        root.put("selected", project.selectedIndex)
        val layers = JSONArray()
        for (layer in project.layers) layers.put(layerToJson(layer))
        root.put("layers", layers)
        root.put("lighting", lightingToJson(project.lighting))
        root.put("texture", textureToJson(project.texture))
        root.put("animation", animationToJson(project.animation))
        project.morphTarget?.let { target ->
            val arr = JSONArray()
            for (layer in target) arr.put(layerToJson(layer))
            root.put("morphTarget", arr)
        }
        return root.toString(2)
    }

    private fun layerToJson(layer: GradientLayer): JSONObject {
        val l = JSONObject()
        l.put("id", layer.id)
        l.put("name", layer.name)
        l.put("opacity", layer.opacity.toDouble())
        l.put("blend", layer.blend.name)
        l.put("visible", layer.visible)
        val s = layer.spec
        l.put("type", s.type.name)
        l.put("cx", s.centerX.toDouble())
        l.put("cy", s.centerY.toDouble())
        l.put("angle", s.angle.toDouble())
        l.put("scale", s.scale.toDouble())
        l.put("turns", s.turns.toDouble())
        l.put("spread", s.spread.name)
        val stops = JSONArray()
        for (stop in s.stops) {
            stops.put(JSONObject().put("p", stop.position.toDouble()).put("c", hex(stop.color)))
        }
        l.put("stops", stops)

        l.put(
            "mesh",
            JSONObject()
                .put("cols", s.mesh.cols)
                .put("rows", s.mesh.rows)
                .put("colors", JSONArray(s.mesh.colors.map { hex(it) })),
        )
        l.put(
            "gravity",
            JSONObject()
                .put("power", s.gravity.power.toDouble())
                .put(
                    "points",
                    JSONArray(
                        s.gravity.points.map {
                            JSONObject().put("x", it.x.toDouble()).put("y", it.y.toDouble())
                                .put("c", hex(it.color)).put("m", it.mass.toDouble())
                        },
                    ),
                ),
        )
        l.put(
            "path",
            JSONObject()
                .put("width", s.path.width.toDouble())
                .put("softness", s.path.softness.toDouble())
                .put("flow", s.path.flow)
                .put(
                    "points",
                    JSONArray(s.path.points.map { JSONObject().put("x", it.x.toDouble()).put("y", it.y.toDouble()) }),
                ),
        )
        val map = JSONObject().put("invert", s.map.invert).put("gamma", s.map.gamma.toDouble())
        s.map.image?.let { map.put("image", imageToJson(it)) }
        l.put("map", map)
        return l
    }

    private fun lightingToJson(spec: LightingSpec) = JSONObject()
        .put("vignette", spec.vignette.toDouble())
        .put(
            "lights",
            JSONArray(
                spec.lights.map {
                    JSONObject().put("x", it.x.toDouble()).put("y", it.y.toDouble())
                        .put("radius", it.radius.toDouble()).put("intensity", it.intensity.toDouble())
                        .put("color", hex(it.color))
                },
            ),
        )

    private fun animationToJson(a: AnimationSpec) = JSONObject()
        .put("durationMs", a.durationMs)
        .put("fps", a.fps)
        .put("rotate", a.rotate)
        .put("orbit", a.orbit.toDouble())
        .put("flow", a.flow)
        .put("hueCycles", a.hueCycles)
        .put("pulse", a.pulse.toDouble())
        .put("morph", a.morph)

    private fun textureToJson(spec: TextureSpec) = JSONObject()
        .put("grain", spec.grain.toDouble())
        .put("grainSize", spec.grainSize.toDouble())
        .put("noise", spec.noise.toDouble())
        .put("noiseScale", spec.noiseScale.toDouble())
        .put("monochrome", spec.monochrome)
        .put("dither", spec.dither)
        .put("seed", spec.seed)

    private fun imageToJson(image: MapImage): JSONObject {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        deflater.setInput(image.luma, 0, image.width * image.height)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        return JSONObject()
            .put("w", image.width)
            .put("h", image.height)
            .put("data", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
    }

    // ------------------------------------------------------------ reading

    /** Throws [IllegalArgumentException] if the text is not a valid project. */
    fun fromJson(text: String): GradientProject {
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw IllegalArgumentException("Not JSON", e)
        }
        require(root.optString("format") == FORMAT) { "Not a gradient project" }
        require(root.optInt("version", 0) in 1..VERSION) { "Unsupported version" }

        val layersJson = root.optJSONArray("layers") ?: throw IllegalArgumentException("No layers")
        require(layersJson.length() in 1..MAX_LAYERS) { "Bad layer count" }
        val layers = (0 until layersJson.length()).map { layerFromJson(layersJson.getJSONObject(it), it) }

        return GradientProject(
            layers = layers,
            selectedIndex = root.optInt("selected", 0).coerceIn(0, layers.lastIndex),
            width = root.optInt("width", 1080).coerceIn(16, 8192),
            height = root.optInt("height", 1080).coerceIn(16, 8192),
            background = parseHex(root.optString("background", "#00000000"), 0),
            lighting = root.optJSONObject("lighting")?.let { lightingFromJson(it) } ?: LightingSpec(),
            texture = root.optJSONObject("texture")?.let { textureFromJson(it) } ?: TextureSpec(),
            animation = root.optJSONObject("animation")?.let { animationFromJson(it) } ?: AnimationSpec(),
            morphTarget = root.optJSONArray("morphTarget")?.let { arr ->
                if (arr.length() in 1..MAX_LAYERS) (0 until arr.length()).map { layerFromJson(arr.getJSONObject(it), it) } else null
            },
        )
    }

    private fun animationFromJson(o: JSONObject) = AnimationSpec(
        durationMs = o.optInt("durationMs", 3000).coerceIn(500, 10_000),
        fps = o.optInt("fps", 24).coerceIn(5, 60),
        rotate = o.optInt("rotate", 0).coerceIn(-8, 8),
        orbit = o.optDouble("orbit", 0.0).toFloat().coerceIn(0f, 0.5f),
        flow = o.optInt("flow", 0).coerceIn(-8, 8),
        hueCycles = o.optInt("hueCycles", 0).coerceIn(-4, 4),
        pulse = o.optDouble("pulse", 0.0).toFloat().coerceIn(0f, 0.5f),
        morph = o.optBoolean("morph", false),
    )

    private fun layerFromJson(l: JSONObject, i: Int): GradientLayer {
        val stopsJson = l.optJSONArray("stops")
        val stops = if (stopsJson != null && stopsJson.length() >= 1) {
            (0 until stopsJson.length()).map { j ->
                val st = stopsJson.getJSONObject(j)
                ColorStop(
                    st.optDouble("p", 0.0).toFloat().coerceIn(0f, 1f),
                    parseHex(st.optString("c", "#FF000000"), 0xFF000000.toInt()),
                )
            }
        } else {
            GradientSpec().stops
        }
        val defaults = GradientSpec()
        return GradientLayer(
            id = l.optLong("id", (i + 1).toLong()),
            name = l.optString("name", "Layer ${i + 1}"),
            opacity = l.optDouble("opacity", 1.0).toFloat().coerceIn(0f, 1f),
            blend = enumOr(l.optString("blend"), BlendMode.Normal),
            visible = l.optBoolean("visible", true),
            spec = GradientSpec(
                type = enumOr(l.optString("type"), GradientType.Linear),
                stops = stops,
                centerX = l.optDouble("cx", 0.5).toFloat(),
                centerY = l.optDouble("cy", 0.5).toFloat(),
                angle = l.optDouble("angle", 45.0).toFloat(),
                scale = l.optDouble("scale", 1.0).toFloat().coerceIn(0.05f, 10f),
                turns = l.optDouble("turns", 3.0).toFloat().coerceIn(0.25f, 16f),
                spread = enumOr(l.optString("spread"), Spread.Clamp),
                mesh = l.optJSONObject("mesh")?.let { meshFromJson(it) } ?: defaults.mesh,
                gravity = l.optJSONObject("gravity")?.let { gravityFromJson(it) } ?: defaults.gravity,
                path = l.optJSONObject("path")?.let { pathFromJson(it) } ?: defaults.path,
                map = l.optJSONObject("map")?.let { mapFromJson(it) } ?: defaults.map,
            ),
        )
    }

    private fun meshFromJson(m: JSONObject): MeshSpec {
        val cols = m.optInt("cols", 3).coerceIn(MeshSpec.MIN_SIZE, MeshSpec.MAX_SIZE)
        val rows = m.optInt("rows", 3).coerceIn(MeshSpec.MIN_SIZE, MeshSpec.MAX_SIZE)
        val arr = m.optJSONArray("colors")
        val colors = (0 until cols * rows).map { i ->
            if (arr != null && i < arr.length()) parseHex(arr.optString(i), 0xFF808080.toInt()) else 0xFF808080.toInt()
        }
        return MeshSpec(cols, rows, colors)
    }

    private fun gravityFromJson(g: JSONObject): GravitySpec {
        val arr = g.optJSONArray("points")
        val points = if (arr == null) GravitySpec().points else (0 until minOf(arr.length(), MAX_GRAVITY_POINTS)).map {
            val p = arr.getJSONObject(it)
            GravityPoint(
                x = p.optDouble("x", 0.5).toFloat(),
                y = p.optDouble("y", 0.5).toFloat(),
                color = parseHex(p.optString("c", "#FFFFFFFF"), -1),
                mass = p.optDouble("m", 1.0).toFloat().coerceIn(0.05f, 20f),
            )
        }
        return GravitySpec(points, g.optDouble("power", 2.0).toFloat().coerceIn(0.5f, 8f))
    }

    private fun pathFromJson(p: JSONObject): PathSpec {
        val arr = p.optJSONArray("points")
        val points = if (arr == null) PathSpec().points else (0 until minOf(arr.length(), MAX_PATH_POINTS)).map {
            val q = arr.getJSONObject(it)
            PathPoint(q.optDouble("x", 0.5).toFloat(), q.optDouble("y", 0.5).toFloat())
        }
        return PathSpec(
            points = points,
            width = p.optDouble("width", 0.1).toFloat().coerceIn(0.005f, 1f),
            softness = p.optDouble("softness", 0.6).toFloat().coerceIn(0f, 1f),
            flow = p.optBoolean("flow", false),
        )
    }

    private fun mapFromJson(m: JSONObject): MapSpec {
        val image = m.optJSONObject("image")?.let { imageFromJson(it) }
        return MapSpec(image, m.optBoolean("invert", false), m.optDouble("gamma", 1.0).toFloat().coerceIn(0.2f, 5f))
    }

    /** Returns null (the map simply starts empty) if the stored image is damaged or inconsistent. */
    private fun imageFromJson(o: JSONObject): MapImage? {
        return try {
            val w = o.getInt("w")
            val h = o.getInt("h")
            require(w in 1..MAX_MAP_SIDE && h in 1..MAX_MAP_SIDE)
            val expected = w * h
            val compressed = Base64.decode(o.getString("data"), Base64.DEFAULT)
            val out = ByteArrayOutputStream(expected)
            val buffer = ByteArray(8 * 1024)
            InflaterInputStream(compressed.inputStream()).use { input ->
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    require(out.size() <= expected) // refuse oversized (corrupt or malicious) data
                }
            }
            if (out.size() == expected) MapImage(w, h, out.toByteArray()) else null
        } catch (e: Exception) {
            null
        }
    }

    private fun lightingFromJson(o: JSONObject): LightingSpec {
        val arr = o.optJSONArray("lights")
        val lights = if (arr == null) emptyList() else (0 until minOf(arr.length(), MAX_LIGHTS)).map {
            val l = arr.getJSONObject(it)
            Light(
                x = l.optDouble("x", 0.5).toFloat(),
                y = l.optDouble("y", 0.5).toFloat(),
                radius = l.optDouble("radius", 0.45).toFloat().coerceIn(0.02f, 2f),
                intensity = l.optDouble("intensity", 0.8).toFloat().coerceIn(0f, 2f),
                color = parseHex(l.optString("color", "#FFFFF1D6"), 0xFFFFF1D6.toInt()),
            )
        }
        return LightingSpec(lights, o.optDouble("vignette", 0.0).toFloat().coerceIn(0f, 1f))
    }

    private fun textureFromJson(o: JSONObject) = TextureSpec(
        grain = o.optDouble("grain", 0.0).toFloat().coerceIn(0f, 1f),
        grainSize = o.optDouble("grainSize", 1.0).toFloat().coerceIn(1f, 12f),
        noise = o.optDouble("noise", 0.0).toFloat().coerceIn(0f, 1f),
        noiseScale = o.optDouble("noiseScale", 0.25).toFloat().coerceIn(0.02f, 1f),
        monochrome = o.optBoolean("monochrome", true),
        dither = o.optBoolean("dither", false),
        seed = o.optInt("seed", 1),
    )

    private inline fun <reified E : Enum<E>> enumOr(name: String, fallback: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: fallback
}
