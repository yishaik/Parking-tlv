package il.yishai.parking.car

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.location.Location
import android.net.Uri
import android.os.Looper
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarLocation
import androidx.car.app.model.ItemList
import androidx.car.app.model.Metadata
import androidx.car.app.model.Place
import androidx.car.app.model.PlaceListMapTemplate
import androidx.car.app.model.PlaceMarker
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import il.yishai.parking.Garage
import il.yishai.parking.Geo
import il.yishai.parking.LastSpotStore
import il.yishai.parking.LocationHelper
import il.yishai.parking.ParkingRepository
import il.yishai.parking.Perms
import il.yishai.parking.distanceText
import kotlinx.coroutines.launch

class ParkingCarAppService : CarAppService() {
    // Personal sideloaded app: accept any host (Android Auto, emulator DHU).
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    override fun onCreateSession(): Session = ParkingSession()
}

class ParkingSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        lifecycle.addObserver(DriveTracker(carContext.applicationContext))
        return NearbyScreen(carContext)
    }
}

/** While the car screen session is alive, track location; when it ends (car disconnected), save the spot. */
class DriveTracker(private val ctx: Context) : DefaultLifecycleObserver {
    private val fused = LocationServices.getFusedLocationProviderClient(ctx)
    private var last: Location? = null
    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) { result.lastLocation?.let { last = it } }
    }

    @SuppressLint("MissingPermission")
    override fun onCreate(owner: LifecycleOwner) {
        if (!Perms.hasLocation(ctx)) return
        try {
            fused.requestLocationUpdates(
                LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10_000).build(), callback, Looper.getMainLooper(),
            )
        } catch (_: SecurityException) {}
    }

    override fun onDestroy(owner: LifecycleOwner) {
        try {
            fused.removeLocationUpdates(callback)
            last?.let { il.yishai.parking.Parking.record(ctx, it.latitude, it.longitude, "android-auto") }
        } catch (e: Exception) { il.yishai.parking.CrashLog.error("android auto: save on disconnect", e) }
    }
}

class NearbyScreen(carContext: CarContext) : Screen(carContext) {
    private val repo = ParkingRepository.get(carContext)
    private val store = LastSpotStore(carContext.applicationContext)
    private var garages: List<Garage>? = null
    private var zone: Int? = null

    init { reload() }

    private fun reload() {
        lifecycleScope.launch {
            val loc = LocationHelper.current(carContext)
            val (list, _) = repo.garages()
            garages = if (loc != null) {
                list.map { it.copy(distance = Geo.distance(loc.latitude, loc.longitude, it.lat, it.lon)) }.sortedBy { it.distance }
            } else list
            zone = loc?.let { repo.zoneAt(it.latitude, it.longitude) }
            boundary = loc?.let { repo.onPermitBoundary(it.latitude, it.longitude) } ?: false
            lastLoc = loc
            invalidate()
        }
    }

    private var boundary = false
    private var lastLoc: android.location.Location? = null

    private fun title(): String {
        val z = zone
        val ex = repo.rules.explain(z, java.time.LocalDateTime.now(), boundary)
        return if (z == null && !boundary) "חניה בת״א" else "אזור ${z ?: ""} · ${ex.headline}"
    }

    private fun parkHere() {
        val l = lastLoc
        if (l == null) { androidx.car.app.CarToast.makeText(carContext, "אין מיקום כרגע", androidx.car.app.CarToast.LENGTH_SHORT).show(); return }
        try { il.yishai.parking.Parking.record(carContext, l.latitude, l.longitude, "android-auto") }
        catch (e: Exception) { il.yishai.parking.CrashLog.error("android auto: park here", e); androidx.car.app.CarToast.makeText(carContext, "השמירה נכשלה", androidx.car.app.CarToast.LENGTH_SHORT).show(); return }
        androidx.car.app.CarToast.makeText(carContext, "נשמר. תבחר צבע מדרכה בטלפון", androidx.car.app.CarToast.LENGTH_LONG).show()
        invalidate()
    }

    private fun navigate(lat: Double, lon: Double) {
        try { carContext.startCarApp(Intent(CarContext.ACTION_NAVIGATE, Uri.parse("geo:$lat,$lon"))) } catch (_: Exception) {}
    }

    private fun row(title: String, text: String, lat: Double, lon: Double, label: String?): Row {
        val marker = PlaceMarker.Builder().apply { if (label != null) setLabel(label) }.build()
        val place = Place.Builder(CarLocation.create(lat, lon)).setMarker(marker).build()
        return Row.Builder().setTitle(title).addText(text.ifBlank { " " })
            .setMetadata(Metadata.Builder().setPlace(place).build())
            .setOnClickListener { navigate(lat, lon) }
            .build()
    }

    override fun onGetTemplate(): Template {
        val builder = PlaceListMapTemplate.Builder()
            .setTitle(title())
            .setHeaderAction(Action.APP_ICON)
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(Action.Builder().setTitle("חניתי").setOnClickListener { parkHere() }.build())
                    .addAction(Action.Builder().setTitle("רענן").setOnClickListener { garages = null; invalidate(); reload() }.build())
                    .build(),
            )
        val list = garages ?: return builder.setLoading(true).build()

        val limit = carContext.getCarService(ConstraintManager::class.java)
            .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_PLACE_LIST)
        val items = ItemList.Builder()
        var n = 0
        store.load()?.let { s ->
            items.addItem(row("הרכב שלי", "חנית ${s.timeText()}", s.lat, s.lon, "P")); n++
        }
        for (g in list) {
            if (n >= limit) break
            val status = il.yishai.parking.statusLabel(g.status)
            items.addItem(row(g.name, listOf(status, distanceText(g.distance)).filter { it.isNotBlank() }.joinToString(" · "), g.lat, g.lon, null))
            n++
        }
        if (n == 0) items.setNoItemsMessage("לא נמצאו חניונים")
        return builder.setItemList(items.build()).setCurrentLocationEnabled(Perms.hasLocation(carContext)).build()
    }
}
