package com.example.birdspotter.ui.home

import android.Manifest
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.birdspotter.R
import com.example.birdspotter.databinding.FragmentHomeBinding
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolylineOptions
import com.google.maps.DirectionsApi
import com.google.maps.GeoApiContext
import com.google.maps.model.DirectionsResult
import com.google.maps.model.LatLng as GMapsLatLng
import com.google.android.gms.maps.model.LatLngBounds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.*
import kotlin.math.roundToInt

class HomeFragment : Fragment(), OnMapReadyCallback {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var mapView: MapView
    private var googleMap: GoogleMap? = null

    private val EBIRD_API_KEY = "iui6vsljmcjt"
    private val hotspots = mutableListOf<BirdHotspot>()
    private val observations = mutableListOf<BirdObservation>()
    private var distanceRadius = 10.0 // Default radius in kilometers

    private val okHttpClient by lazy { OkHttpClient() }

    // Permissions request launcher using ActivityResultContracts
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            fetchAllData()
        } else {
            showError("Location permissions are required to display bird hotspots.")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity())

        // Initialize MapView
        mapView = binding.mapView
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync(this)

        // Check permissions and load data
        if (hasAllPermissions()) {
            fetchAllData()
        } else {
            requestPermissions()
        }

        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        mapView.onDestroy()
        _binding = null
    }

    private fun hasAllPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    private fun fetchAllData() {
        getUserLocation()
    }

    private fun getUserLocation() {
        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
                location?.let {
                    viewLifecycleOwner.lifecycleScope.launch {
                        val regionCode = getRegionCode(it.latitude, it.longitude)
                        fetchObservations(regionCode, it.latitude, it.longitude)
                        fetchHotspots(regionCode, it.latitude, it.longitude)
                        setupMap(it.latitude, it.longitude)
                    }
                } ?: showError("Unable to retrieve location. Please ensure location services are enabled.")
            }.addOnFailureListener {
                showError("Error retrieving location. Please try again.")
            }
        }
    }

    private suspend fun getRegionCode(lat: Double, lon: Double): String {
        return withContext(Dispatchers.IO) {
            try {
                val geocoder = Geocoder(requireContext(), Locale.getDefault())
                val addressList = geocoder.getFromLocation(lat, lon, 1)
                addressList?.firstOrNull()?.countryCode ?: "ZA"
            } catch (e: Exception) {
                Log.e("HomeFragment", "Geocoder error: ${e.message}", e)
                "ZA"
            }
        }
    }

    private suspend fun fetchObservations(regionCode: String, lat: Double, lon: Double) {
        val url = "https://api.ebird.org/v2/ref/obs/${regionCode}?lat=$lat&lng=$lon&key=$EBIRD_API_KEY"
        Log.d("HomeFragment", "Fetching observations with URL: $url")

        try {
            val request = Request.Builder().url(url).get().build()

            val response = withContext(Dispatchers.IO) {
                okHttpClient.newCall(request).execute()
            }

            if (response.isSuccessful) {
                val body = response.body?.string()
                if (body != null && body.isNotEmpty()) {
                    val observationList = parseObservations(body, lat, lon).take(10)
                    withContext(Dispatchers.Main) {
                        observations.clear()
                        observations.addAll(observationList)
                        displayObservationsOnMap(observationList)
                    }
                } else {
                    showError("No observations found in this location.")
                }
            } else {
                throw Exception("Failed to fetch observations: ${response.message}")
            }
        } catch (e: Exception) {
            Log.e("HomeFragment", "Error fetching observations", e)
            showError("Error fetching observations. Please try again.")
        }
    }

    private suspend fun fetchHotspots(regionCode: String, lat: Double, lon: Double) {
        val url = "https://api.ebird.org/v2/ref/hotspot/${regionCode}?lat=$lat&lng=$lon&key=$EBIRD_API_KEY"
        Log.d("HomeFragment", "Fetching hotspots with URL: $url")

        try {
            val request = Request.Builder().url(url).get().build()

            val response = withContext(Dispatchers.IO) {
                okHttpClient.newCall(request).execute()
            }

            if (response.isSuccessful) {
                val body = response.body?.string()
                if (body != null && body.isNotEmpty()) {
                    val hotspotList = parseHotspots(body, lat, lon).take(10)
                    withContext(Dispatchers.Main) {
                        hotspots.clear()
                        hotspots.addAll(hotspotList)
                        displayHotspotsOnMap(hotspotList)
                    }
                } else {
                    showError("No hotspots found in this location.")
                }
            } else {
                throw Exception("Failed to fetch hotspots: ${response.message}")
            }
        } catch (e: Exception) {
            Log.e("HomeFragment", "Error fetching hotspots", e)
            showError("Error fetching hotspots. Please try again.")
        }
    }

    private fun parseObservations(responseData: String, lat: Double, lon: Double): List<BirdObservation> {
        val jsonArray = JSONArray(responseData)
        val observationList = mutableListOf<BirdObservation>()

        for (i in 0 until jsonArray.length()) {
            val observationJson = jsonArray.getJSONObject(i)

            val speciesName = observationJson.optString("speciesName", "Unknown Species")
            val latitude = observationJson.optDouble("latitude", 0.0)
            val longitude = observationJson.optDouble("longitude", 0.0)

            val distance = calculateDistance(lat, lon, latitude, longitude)
            if (distance <= distanceRadius) {
                val observation = BirdObservation(speciesName, latitude, longitude, distance)
                observationList.add(observation)
            }
        }

        return observationList
    }

    private fun parseHotspots(responseData: String, lat: Double, lon: Double): List<BirdHotspot> {
        val jsonArray = JSONArray(responseData)
        val hotspotList = mutableListOf<BirdHotspot>()

        for (i in 0 until jsonArray.length()) {
            val hotspotJson = jsonArray.getJSONObject(i)

            val locId = hotspotJson.optString("locId", "Unknown")
            val name = hotspotJson.optString("name", "Unnamed Location")
            val latitude = hotspotJson.optDouble("latitude", 0.0)
            val longitude = hotspotJson.optDouble("longitude", 0.0)
            val countryName = hotspotJson.optString("countryName", "Unknown Country")
            val subnational1Name = hotspotJson.optString("subnational1Name", "Unknown Region")

            val distance = calculateDistance(lat, lon, latitude, longitude)
            if (distance <= distanceRadius) {
                val hotspot = BirdHotspot(locId, name, latitude, longitude, countryName, subnational1Name, distance)
                hotspotList.add(hotspot)
            }
        }

        return hotspotList
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val results = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, results)
        return (results[0] / 1000.0).roundToInt().toDouble() // Convert to kilometers and round
    }

    private fun displayObservationsOnMap(observationList: List<BirdObservation>) {
        googleMap?.let { map ->
            observationList.forEach { observation ->
                val position = LatLng(observation.latitude, observation.longitude)
                map.addMarker(
                    MarkerOptions()
                        .position(position)
                        .title(observation.speciesName)
                        .snippet("Observation within ${observation.distance} km")
                )
            }
        }
    }

    private fun displayHotspotsOnMap(hotspotList: List<BirdHotspot>) {
        googleMap?.let { map ->
            hotspotList.forEach { hotspot ->
                val position = LatLng(hotspot.latitude, hotspot.longitude)
                val marker = map.addMarker(
                    MarkerOptions()
                        .position(position)
                        .title(hotspot.name)
                        .snippet("${hotspot.subnational1Name}, ${hotspot.countryName}")
                )
                marker?.tag = hotspot
            }
        }

        googleMap?.setOnMarkerClickListener { marker ->
            val hotspot = marker.tag as? BirdHotspot
            marker.showInfoWindow()
            if (hotspot != null) {
                fetchDirectionsToHotspot(LatLng(hotspot.latitude, hotspot.longitude))
            }
            true
        }
    }

    private fun setupMap(userLat: Double, userLon: Double) {
        googleMap?.let { map ->
            val userLocation = LatLng(userLat, userLon)
            map.uiSettings.isZoomControlsEnabled = true
            map.uiSettings.isMyLocationButtonEnabled = true
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(userLocation, 10f))

            map.setOnMapClickListener { destination ->
                fetchDirectionsToHotspot(destination)
            }
        }
    }

    private fun fetchDirectionsToHotspot(destination: LatLng) {
        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // Show a notification message to the user
            Toast.makeText(requireContext(), "Location permissions are required to access this feature.", Toast.LENGTH_LONG).show()

            // Request the permissions again
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
            return
        }

        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                val userLatLng = LatLng(location.latitude, location.longitude)

                lifecycleScope.launch(Dispatchers.IO) {
                    val context = GeoApiContext.Builder()
                        .apiKey("YOUR_GOOGLE_MAPS_API_KEY")
                        .build()

                    try {
                        val result: DirectionsResult = DirectionsApi.newRequest(context)
                            .origin(GMapsLatLng(userLatLng.latitude, userLatLng.longitude))
                            .destination(GMapsLatLng(destination.latitude, destination.longitude))
                            .await()

                        withContext(Dispatchers.Main) {
                            drawRoute(result)
                        }
                    } catch (e: Exception) {
                        Log.e("HomeFragment", "Directions API error: ${e.message}", e)
                        withContext(Dispatchers.Main) {
                            showError("Unable to get directions. Please try again.")
                        }
                    }
                }
            }
        }
    }

    private fun drawRoute(result: DirectionsResult) {
        val path = result.routes[0].overviewPolyline.decodePath().map {
            LatLng(it.lat, it.lng)
        }
        googleMap?.addPolyline(PolylineOptions().addAll(path).color(ContextCompat.getColor(requireContext(), R.color.route_color)))
        googleMap?.moveCamera(CameraUpdateFactory.newLatLngBounds(calculateLatLngBounds(path), 100))
    }

    private fun calculateLatLngBounds(path: List<LatLng>): LatLngBounds {
        val builder = LatLngBounds.Builder()
        path.forEach { builder.include(it) }
        return builder.build()
    }

    private fun showError(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    data class BirdHotspot(
        val locId: String,
        val name: String,
        val latitude: Double,
        val longitude: Double,
        val countryName: String,
        val subnational1Name: String,
        val distance: Double
    )

    data class BirdObservation(
        val speciesName: String,
        val latitude: Double,
        val longitude: Double,
        val distance: Double
    )
}
