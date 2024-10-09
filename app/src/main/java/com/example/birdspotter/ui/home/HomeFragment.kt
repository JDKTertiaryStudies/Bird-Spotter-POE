package com.example.birdspotter.ui.home

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.birdspotter.R
import com.example.birdspotter.databinding.FragmentHomeBinding
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.*
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

class HomeFragment : Fragment(), OnMapReadyCallback {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var mapView: MapView
    private var googleMap: GoogleMap? = null

    private val EBIRD_API_KEY = "iui6vsljmcjt"
    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private var distanceRadius = 50.0 // Default radius
    private var currentHotspotLimit = 10 // Initial limit of hotspots

    // Permissions request launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            fetchAllData()
        } else {
            showError("Location permissions are required to display bird observations.")
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

        // Set up RecyclerView for observations
        binding.birdObservationRecyclerView.layoutManager = LinearLayoutManager(requireContext())

        // Set up buttons to adjust hotspot radius
        setupHotspotRangeButtons()

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

    // Check if location permission is granted
    private fun hasAllPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    // Request location permissions
    private fun requestPermissions() {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    // Fetch user location and initiate data fetching
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
                        val lat = it.latitude
                        val lon = it.longitude
                        fetchRawObservations(lat, lon) // Fetch raw data
                        fetchRawHotspots(lat, lon) // Fetch raw data
                        setupMap(lat, lon)
                    }
                } ?: showError("Unable to retrieve location. Please ensure location services are enabled.")
            }.addOnFailureListener {
                showError("Error retrieving location. Please try again.")
            }
        }
    }

    // Setup buttons for increasing/decreasing the radius and hotspot limit
    private fun setupHotspotRangeButtons() {
        val increaseButton = binding.increaseRadiusButton
        val decreaseButton = binding.decreaseRadiusButton

        increaseButton.setOnClickListener {
            distanceRadius += 50
            currentHotspotLimit += 10 // Increase hotspot limit by 10
            fetchAllData() // Refresh hotspots with the new radius and limit
            Toast.makeText(requireContext(), "Radius increased to ${distanceRadius}km, showing up to $currentHotspotLimit hotspots", Toast.LENGTH_SHORT).show()
        }

        decreaseButton.setOnClickListener {
            if (distanceRadius > 50) {
                distanceRadius -= 50
                currentHotspotLimit = maxOf(10, currentHotspotLimit - 10) // Decrease hotspot limit but keep it above 10
                fetchAllData() // Refresh hotspots with the new radius and limit
                Toast.makeText(requireContext(), "Radius decreased to ${distanceRadius}km, showing up to $currentHotspotLimit hotspots", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "Cannot decrease radius below 50km", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Fetch raw bird observations data
    private suspend fun fetchRawObservations(lat: Double, lon: Double) {
        val url = "https://api.ebird.org/v2/data/obs/geo/recent?lat=$lat&lng=$lon&sort=species"
        Log.d("HomeFragment", "Fetching observations with URL: $url")

        try {
            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("X-eBirdApiToken", EBIRD_API_KEY)
                .build()

            val response = withContext(Dispatchers.IO) {
                okHttpClient.newCall(request).execute()
            }

            if (response.isSuccessful) {
                val body = response.body?.string()
                body?.let {
                    parseAndDisplayObservations(it) // Parse and display observations
                } ?: showError("No observations found in this location.")
            } else {
                throw Exception("Failed to fetch observations: ${response.message}")
            }
        } catch (e: Exception) {
            Log.e("HomeFragment", "Error fetching observations", e)
            showError("Error fetching observations. Please try again.")
        }
    }

    // Fetch raw bird hotspots data
    private suspend fun fetchRawHotspots(lat: Double, lon: Double) {
        val url = "https://api.ebird.org/v2/ref/hotspot/geo?lat=$lat&lng=$lon"
        Log.d("HomeFragment", "Fetching hotspots with URL: $url")

        try {
            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("X-eBirdApiToken", EBIRD_API_KEY)
                .build()

            val response = withContext(Dispatchers.IO) {
                okHttpClient.newCall(request).execute()
            }

            if (response.isSuccessful) {
                val body = response.body?.string()
                Log.d("HomeFragment", "Hotspot response: $body") // Log the raw response

                body?.let {
                    parseAndDisplayHotspots(it) // Parse and display hotspots
                } ?: showError("No hotspots found in this location.")
            } else {
                Log.e("HomeFragment", "Failed to fetch hotspots: ${response.code} - ${response.message}")
                throw Exception("Failed to fetch hotspots: ${response.message}")
            }
        } catch (e: Exception) {
            Log.e("HomeFragment", "Error fetching hotspots", e)
            showError("Error fetching hotspots. Please try again.")
        }
    }

    // Parse and display bird observations
    private suspend fun parseAndDisplayObservations(rawData: String) {
        val observationList = parseObservations(rawData)
        withContext(Dispatchers.Main) {
            displayObservations(observationList) // Update UI with parsed data
        }
    }

    // Parse and display bird hotspots
    private suspend fun parseAndDisplayHotspots(rawData: String) {
        val hotspotList = parseHotspots(rawData)
        val limitedHotspotList = hotspotList.take(currentHotspotLimit) // Limit the number of displayed hotspots
        withContext(Dispatchers.Main) {
            displayHotspotsOnMap(limitedHotspotList) // Update UI with limited hotspots
        }
    }

    // Parse JSON response for bird observations
    private fun parseObservations(responseData: String): List<BirdObservation> {
        val jsonArray = JSONArray(responseData)
        val observationList = mutableListOf<BirdObservation>()

        for (i in 0 until jsonArray.length()) {
            val observationJson = jsonArray.getJSONObject(i)

            val speciesCode = observationJson.optString("speciesCode", "")
            val commonName = observationJson.optString("comName", "Unknown")
            val scientificName = observationJson.optString("sciName", "Unknown")
            val location = observationJson.optString("locName", "Unknown Location")
            val observationDateTime = observationJson.optString("obsDt", "Unknown Date")
            val latitude = observationJson.optDouble("lat", 0.0)
            val longitude = observationJson.optDouble("lng", 0.0)

            val observation = BirdObservation(
                speciesCode = speciesCode,
                commonName = commonName,
                scientificName = scientificName,
                location = location,
                observationDateTime = observationDateTime,
                latitude = latitude,
                longitude = longitude
            )
            observationList.add(observation)
        }
        return observationList
    }

    // Parse the CSV-like response for bird hotspots
    private fun parseHotspots(responseData: String): List<BirdHotspot> {
        val hotspotList = mutableListOf<BirdHotspot>()

        // Split the response by lines
        val lines = responseData.trim().split("\n")

        for (line in lines) {
            // Split each line by commas
            val fields = line.split(",")

            // Ensure there are enough fields in the line to avoid IndexOutOfBoundsException
            if (fields.size >= 6) {
                val locId = fields[0].trim() // Location ID
                val name = fields[6].trim() // Location name
                val latitude = fields[4].toDoubleOrNull() ?: 0.0 // Latitude
                val longitude = fields[5].toDoubleOrNull() ?: 0.0 // Longitude

                // Create a BirdHotspot object
                val hotspot = BirdHotspot(
                    locId = locId,
                    name = name,
                    latitude = latitude,
                    longitude = longitude
                )
                hotspotList.add(hotspot)
            } else {
                Log.e("HomeFragment", "Invalid data format in line: $line")
            }
        }

        return hotspotList
    }

    // Display bird observations in the RecyclerView
    private fun displayObservations(observationList: List<BirdObservation>) {
        val adapter = BirdObservationAdapter(observationList)
        binding.birdObservationRecyclerView.adapter = adapter
    }

    // Display bird hotspots on the map
    private fun displayHotspotsOnMap(hotspotList: List<BirdHotspot>) {
        googleMap?.let { map ->
            hotspotList.forEach { hotspot ->
                val position = LatLng(hotspot.latitude, hotspot.longitude)
                map.addMarker(
                    MarkerOptions()
                        .position(position)
                        .title(hotspot.name)
                )
            }
        }
    }

    // Setup the map with user location
    private fun setupMap(userLat: Double, userLon: Double) {
        googleMap?.let { map ->
            val userLocation = LatLng(userLat, userLon)
            map.uiSettings.isZoomControlsEnabled = true
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(userLocation, 10f))
        }
    }

    // Show error message
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

    data class BirdObservation(
        val speciesCode: String,
        val commonName: String,
        val scientificName: String,
        val location: String,
        val observationDateTime: String,
        val latitude: Double,
        val longitude: Double
    )

    data class BirdHotspot(
        val locId: String,
        val name: String,
        val latitude: Double,
        val longitude: Double
    )

    // Adapter for RecyclerView
    class BirdObservationAdapter(private val observationList: List<BirdObservation>) :
        RecyclerView.Adapter<BirdObservationAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val commonName: TextView = view.findViewById(R.id.birdComName)
            val scientificName: TextView = view.findViewById(R.id.birdSciName)
            val location: TextView = view.findViewById(R.id.birdLocation)
            val observationDateTime: TextView = view.findViewById(R.id.birdObsDateTime)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_bird_observation, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val observation = observationList[position]
            holder.commonName.text = observation.commonName
            holder.scientificName.text = observation.scientificName
            holder.location.text = observation.location
            holder.observationDateTime.text = observation.observationDateTime
        }

        override fun getItemCount(): Int = observationList.size
    }
}
