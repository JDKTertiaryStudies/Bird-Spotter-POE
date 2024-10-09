package com.example.birdspotter.ui.dashboard

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.birdspotter.R
import com.example.birdspotter.databinding.FragmentDashboardBinding
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.*

class DashboardFragment : Fragment(), OnMapReadyCallback {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private lateinit var storageRef: StorageReference
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var currentUser: FirebaseUser? = null
    private var googleMap: GoogleMap? = null
    private var selectedImageUri: Uri? = null
    private var currentLocation: Location? = null
    private var currentLocationAddress: String? = null

    private val CHANNEL_ID = "Profile_Load_Error"
    private val REGISTRATION_CHANNEL_ID = "Registration_Success"
    private val NOTIFICATION_ID = 101
    private val REGISTRATION_NOTIFICATION_ID = 102

    private val imagePickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            selectedImageUri = result.data?.data
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()
        storageRef = FirebaseStorage.getInstance().reference
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity())
        currentUser = auth.currentUser

        if (currentUser == null) {
            displayLoginForm()
        } else {
            displayUserProfile() // Load user profile first, then fetch location
        }

        setupMapView(savedInstanceState)
        setupToggleButtons()
        requestAllPermissions()

        createNotificationChannels()

        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setupMapView(savedInstanceState: Bundle?) {
        binding.mapView.onCreate(savedInstanceState)
        binding.mapView.getMapAsync(this)

        binding.mapNormalView.setOnClickListener {
            googleMap?.mapType = GoogleMap.MAP_TYPE_NORMAL
            Toast.makeText(requireContext(), "Switched to Normal View", Toast.LENGTH_SHORT).show()
        }

        binding.mapSatelliteView.setOnClickListener {
            googleMap?.mapType = GoogleMap.MAP_TYPE_SATELLITE
            Toast.makeText(requireContext(), "Switched to Satellite View", Toast.LENGTH_SHORT).show()
        }

        binding.map3DView.setOnClickListener {
            googleMap?.mapType = GoogleMap.MAP_TYPE_HYBRID
            Toast.makeText(requireContext(), "Switched to 3D View", Toast.LENGTH_SHORT).show()
        }

        binding.searchButton.setOnClickListener {
            searchAndAddHotspot(binding.searchInput.text.toString().trim())
        }
    }

    private fun setupToggleButtons() {
        binding.userDetailsToggle.setOnClickListener {
            setSelectedToggle(binding.userDetailsToggle)
            displayUserProfile()
        }

        binding.formDetailsToggle.setOnClickListener {
            setSelectedToggle(binding.formDetailsToggle)
            displayAddBirdForm()
        }

        binding.birdObservationsToggle.setOnClickListener {
            setSelectedToggle(binding.birdObservationsToggle)
            displayBirdObservationList()
        }
    }

    private fun setSelectedToggle(selectedToggle: TextView) {
        binding.userDetailsToggle.setBackgroundResource(R.drawable.toggle_unselected)
        binding.formDetailsToggle.setBackgroundResource(R.drawable.toggle_unselected)
        binding.birdObservationsToggle.setBackgroundResource(R.drawable.toggle_unselected)
        selectedToggle.setBackgroundResource(R.drawable.toggle_selected)
    }

    private fun requestAllPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (ActivityCompat.checkSelfPermission(
                requireContext(), Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (ActivityCompat.checkSelfPermission(
                requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        if (ActivityCompat.checkSelfPermission(
                requireContext(), Manifest.permission.READ_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(
                    requireContext(), Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(requireActivity(), permissionsToRequest.toTypedArray(), 1)
        } else {
            getUserLocation() // Fetch the user's location if all permissions are granted
        }
    }

    private fun getUserLocation() {
        if (ActivityCompat.checkSelfPermission(
                requireContext(), Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
                if (location != null) {
                    currentLocation = location
                    val userLatLng = LatLng(location.latitude, location.longitude)

                    // Convert the location to a human-readable address
                    lifecycleScope.launch {
                        currentLocationAddress = getAddressFromCoordinates(location.latitude, location.longitude)
                        // Now update the profile with the address without "Location: " prefix
                        updateProfileWithAddress(currentLocationAddress)
                    }

                    // Zoom the map to the user's location
                    googleMap?.moveCamera(com.google.android.gms.maps.CameraUpdateFactory.newLatLngZoom(userLatLng, 15f))

                    Toast.makeText(requireContext(), "Location retrieved: ${location.latitude}, ${location.longitude}", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "Unable to retrieve location", Toast.LENGTH_SHORT).show()
                }
            }.addOnFailureListener {
                Toast.makeText(requireContext(), "Error retrieving location", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private suspend fun getAddressFromCoordinates(lat: Double, lon: Double): String? {
        return withContext(Dispatchers.IO) {
            try {
                val geocoder = Geocoder(requireContext(), Locale.getDefault())
                val addresses = geocoder.getFromLocation(lat, lon, 1)
                if (addresses?.isNotEmpty() == true) {
                    addresses[0].getAddressLine(0)
                } else {
                    "Unknown Location"
                }
            } catch (e: IOException) {
                e.printStackTrace()
                "Unknown Location"
            }
        }
    }

    private fun updateProfileWithAddress(address: String?) {
        // Update the TextView in the profile with the address without the "Location: " prefix
        binding.sectionContent.findViewById<TextView>(R.id.userLocation)?.text = address ?: "Unknown"
    }

    private fun searchAndAddHotspot(searchQuery: String) {
        if (searchQuery.isEmpty()) {
            Toast.makeText(requireContext(), "Please enter a location or coordinates.", Toast.LENGTH_SHORT).show()
            return
        }

        val geocoder = Geocoder(requireContext(), Locale.getDefault())

        try {
            val addresses = geocoder.getFromLocationName(searchQuery, 1)
            if (addresses != null && addresses.isNotEmpty()) {
                val address = addresses[0]
                val latLng = LatLng(address.latitude, address.longitude)

                googleMap?.addMarker(MarkerOptions().position(latLng).title("Favorite Hotspot"))
                googleMap?.animateCamera(com.google.android.gms.maps.CameraUpdateFactory.newLatLngZoom(latLng, 12f))

                Toast.makeText(requireContext(), "Hotspot added at: ${address.getAddressLine(0)}", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "Location not found. Please try again.", Toast.LENGTH_SHORT).show()
            }
        } catch (e: IOException) {
            e.printStackTrace()
            Toast.makeText(requireContext(), "Failed to get location.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun displayLoginForm() {
        binding.sectionContent.removeAllViews()
        val loginView = layoutInflater.inflate(R.layout.fragment_login, binding.sectionContent, false)

        val emailInput = loginView.findViewById<EditText>(R.id.emailInput)
        val passwordInput = loginView.findViewById<EditText>(R.id.passwordInput)
        val loginButton = loginView.findViewById<Button>(R.id.loginButton)
        val registerButton = loginView.findViewById<Button>(R.id.registerButton)

        loginButton.setOnClickListener {
            val email = emailInput.text.toString().trim()
            val password = passwordInput.text.toString().trim()

            if (email.isNotEmpty() && password.isNotEmpty()) {
                auth.signInWithEmailAndPassword(email, password)
                    .addOnCompleteListener { task ->
                        if (task.isSuccessful) {
                            currentUser = auth.currentUser
                            displayUserProfile()
                            Toast.makeText(context, "Login Successful", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Login Failed: ${task.exception?.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
            } else {
                Toast.makeText(context, "Please fill in all fields", Toast.LENGTH_SHORT).show()
            }
        }

        registerButton.setOnClickListener {
            displayRegisterForm()
        }

        binding.sectionContent.addView(loginView)
    }

    private fun displayRegisterForm() {
        binding.sectionContent.removeAllViews()
        val registerView = layoutInflater.inflate(R.layout.fragment_register, binding.sectionContent, false)

        val nameInput = registerView.findViewById<EditText>(R.id.nameInput)
        val emailInput = registerView.findViewById<EditText>(R.id.emailRegisterInput)
        val passwordInput = registerView.findViewById<EditText>(R.id.passwordRegisterInput)
        val registerButton = registerView.findViewById<Button>(R.id.submitRegisterButton)

        registerButton.setOnClickListener {
            val name = nameInput.text.toString().trim()
            val email = emailInput.text.toString().trim()
            val password = passwordInput.text.toString().trim()

            if (name.isNotEmpty() && email.isNotEmpty() && password.isNotEmpty()) {
                auth.createUserWithEmailAndPassword(email, password)
                    .addOnCompleteListener { task ->
                        if (task.isSuccessful) {
                            currentUser = auth.currentUser
                            val user = hashMapOf(
                                "userId" to currentUser!!.uid,
                                "name" to name,
                                "email" to email
                            )

                            db.collection("users").document(currentUser!!.uid).set(user, SetOptions.merge())
                                .addOnSuccessListener {
                                    sendRegistrationSuccessNotification()
                                    displayUserProfile()
                                }
                                .addOnFailureListener {
                                    Toast.makeText(context, "Failed to save user data", Toast.LENGTH_SHORT).show()
                                }
                        } else {
                            Toast.makeText(context, "Registration Failed: ${task.exception?.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
            } else {
                Toast.makeText(context, "Please fill in all fields", Toast.LENGTH_SHORT).show()
            }
        }

        binding.sectionContent.addView(registerView)
    }

    private fun sendRegistrationSuccessNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(
                    requireContext(), Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(requireActivity(), arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
                return
            }
        }

        val builder = NotificationCompat.Builder(requireContext(), REGISTRATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_check_circle)
            .setContentTitle("Registration Successful")
            .setContentText("Welcome to BirdSpotter! You are now logged in.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        with(NotificationManagerCompat.from(requireContext())) {
            notify(REGISTRATION_NOTIFICATION_ID, builder.build())
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val profileLoadErrorChannel = NotificationChannel(
                CHANNEL_ID, "Profile Load Error", NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications for profile load errors"
            }

            val registrationSuccessChannel = NotificationChannel(
                REGISTRATION_CHANNEL_ID, "Registration Success", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for successful registration"
            }

            val notificationManager: NotificationManager =
                requireContext().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(profileLoadErrorChannel)
            notificationManager.createNotificationChannel(registrationSuccessChannel)
        }
    }

    private fun displayUserProfile() {
        binding.sectionContent.removeAllViews()
        val userProfileView = layoutInflater.inflate(R.layout.user_details_layout, binding.sectionContent, false)

        val profileImage = userProfileView.findViewById<ImageView>(R.id.profileImage)
        val userName = userProfileView.findViewById<TextView>(R.id.userName)
        val birdWatcherType = userProfileView.findViewById<TextView>(R.id.userBirdWatcherType)
        val experience = userProfileView.findViewById<TextView>(R.id.userBirdWatchingExperience)
        val locationTextView = userProfileView.findViewById<TextView>(R.id.userLocation)
        val logoutButton = userProfileView.findViewById<Button>(R.id.logoutButton)

        logoutButton.visibility = View.GONE

        if (currentUser != null) {
            db.collection("users").document(currentUser!!.uid).get()
                .addOnSuccessListener { document ->
                    if (document.exists()) {
                        userName.text = document.getString("name")
                        birdWatcherType.text = "${document.getString("birdWatcherType") ?: "Enthusiast"}"
                        experience.text = "${document.getString("experience") ?: "Beginner"}"

                        currentUser?.photoUrl?.let {
                            Glide.with(this).load(it).into(profileImage)
                        }

                        // Display "Pending..." until location is updated
                        locationTextView.text = "Pending..."
                        logoutButton.visibility = View.VISIBLE

                        logoutButton.setOnClickListener {
                            showLogoutBottomSheet()
                        }

                        // Fetch the user's location after loading the profile
                        getUserLocation()
                    } else {
                        showProfileLoadErrorNotification()
                    }
                }
                .addOnFailureListener {
                    showProfileLoadErrorNotification()
                }
        } else {
            displayLoginForm()
        }

        binding.sectionContent.addView(userProfileView)
    }

    private fun displayAddBirdForm() {
        binding.sectionContent.removeAllViews()
        val addBirdFormView = layoutInflater.inflate(R.layout.fragment_add_bird, binding.sectionContent, false)

        val birdNameInput = addBirdFormView.findViewById<EditText>(R.id.birdNameInput)
        val birdNotesInput = addBirdFormView.findViewById<EditText>(R.id.birdNotesInput)
        val selectImageButton = addBirdFormView.findViewById<Button>(R.id.selectImageButton)
        val submitBirdButton = addBirdFormView.findViewById<Button>(R.id.submitBirdButton)

        selectImageButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK)
            intent.type = "image/*"
            imagePickerLauncher.launch(intent)
        }

        submitBirdButton.setOnClickListener {
            val birdName = birdNameInput.text.toString().trim()
            var birdNotes = birdNotesInput.text.toString().trim()

            if (birdName.isNotEmpty() && birdNotes.isNotEmpty()) {
                if (currentUser != null && currentLocationAddress != null) {
                    // Append location address two spaces below the notes
                    birdNotes += "\n\nObserved at: $currentLocationAddress"

                    val birdData = hashMapOf(
                        "birdName" to birdName,
                        "notes" to birdNotes,
                        "location" to currentLocationAddress,
                        "userId" to currentUser!!.uid,
                        "timestamp" to System.currentTimeMillis()
                    )

                    val userObservationsRef = db.collection("observations")
                        .document(currentUser!!.uid)
                        .collection("userObservations")

                    userObservationsRef.add(birdData)
                        .addOnSuccessListener {
                            Toast.makeText(requireContext(), "Bird added successfully", Toast.LENGTH_SHORT).show()
                            displayBirdObservationList()
                        }
                        .addOnFailureListener {
                            Toast.makeText(requireContext(), "Failed to add bird", Toast.LENGTH_SHORT).show()
                        }
                }
            } else {
                Toast.makeText(context, "Please fill in all fields", Toast.LENGTH_SHORT).show()
            }
        }

        binding.sectionContent.addView(addBirdFormView)
    }

    private fun displayBirdObservationList() {
        binding.sectionContent.removeAllViews()
        val birdObservationListView = layoutInflater.inflate(R.layout.user_bird_observations_layout, binding.sectionContent, false)

        val observationRecyclerView = birdObservationListView.findViewById<RecyclerView>(R.id.birdObservationsRecyclerView)
        observationRecyclerView.layoutManager = LinearLayoutManager(requireContext())

        val observationList = mutableListOf<HashMap<String, Any?>>()

        if (currentUser != null) {
            db.collection("observations")
                .document(currentUser!!.uid)
                .collection("userObservations")
                .get()
                .addOnSuccessListener { result ->
                    observationList.clear()
                    for (document in result) {
                        val birdName = document.getString("birdName") ?: "Unknown Bird"
                        val notes = document.getString("notes") ?: "No notes"
                        val location = document.getString("location") ?: "Unknown location"
                        val observation = hashMapOf<String, Any?>(
                            "birdName" to birdName,
                            "notes" to notes,
                            "location" to location
                        )
                        observationList.add(observation)
                    }

                    observationRecyclerView.adapter = BirdObservationListAdapter(observationList)
                }
                .addOnFailureListener {
                    Toast.makeText(context, "Failed to load observations", Toast.LENGTH_SHORT).show()
                }
        }

        binding.sectionContent.addView(birdObservationListView)
    }

    inner class BirdObservationListAdapter(private val observationList: List<HashMap<String, Any?>>) :
        RecyclerView.Adapter<BirdObservationListAdapter.BirdObservationViewHolder>() {

        inner class BirdObservationViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val birdName: TextView = itemView.findViewById(R.id.birdName)
            val birdNotes: TextView = itemView.findViewById(R.id.birdNotes)
            val location: TextView = itemView.findViewById(R.id.location)
            val deleteIcon: ImageView = itemView.findViewById(R.id.deleteIcon)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BirdObservationViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.user_bird_observation, parent, false)
            return BirdObservationViewHolder(view)
        }

        override fun onBindViewHolder(holder: BirdObservationViewHolder, position: Int) {
            val observation = observationList[position]
            holder.birdName.text = observation["birdName"] as String
            holder.birdNotes.text = observation["notes"] as String
            holder.location.text = observation["location"] as String

            holder.deleteIcon.setOnClickListener {
                deleteBirdObservation(observation)
            }
        }

        override fun getItemCount(): Int {
            return observationList.size
        }
    }

    private fun deleteBirdObservation(observation: HashMap<String, Any?>) {
        if (currentUser != null) {
            val userObservationsRef = db.collection("observations")
                .document(currentUser!!.uid)
                .collection("userObservations")

            userObservationsRef.whereEqualTo("birdName", observation["birdName"])
                .get()
                .addOnSuccessListener { result ->
                    for (document in result) {
                        userObservationsRef.document(document.id)
                            .delete()
                            .addOnSuccessListener {
                                Toast.makeText(context, "Observation deleted", Toast.LENGTH_SHORT).show()
                                displayBirdObservationList()
                            }
                            .addOnFailureListener {
                                Toast.makeText(context, "Failed to delete observation", Toast.LENGTH_SHORT).show()
                            }
                    }
                }
                .addOnFailureListener {
                    Toast.makeText(context, "Error finding observation to delete", Toast.LENGTH_SHORT).show()
                }
        }
    }

    private fun showLogoutBottomSheet() {
        val bottomSheetDialog = BottomSheetDialog(requireContext())
        val view = layoutInflater.inflate(R.layout.dialog_logout_confirmation, null)
        bottomSheetDialog.setContentView(view)

        val confirmButton = view.findViewById<Button>(R.id.confirmLogoutButton)
        val cancelButton = view.findViewById<Button>(R.id.cancelLogoutButton)

        confirmButton.setOnClickListener {
            logoutUser()
            bottomSheetDialog.dismiss()
        }

        cancelButton.setOnClickListener {
            bottomSheetDialog.dismiss()
        }

        bottomSheetDialog.show()
    }

    private fun logoutUser() {
        auth.signOut()
        currentUser = null
        displayLoginForm()
        Toast.makeText(requireContext(), "Logged out successfully", Toast.LENGTH_SHORT).show()
    }

    private fun showProfileLoadErrorNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(
                    requireContext(), Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(requireActivity(), arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
                return
            }
        }

        val builder = NotificationCompat.Builder(requireContext(), CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_warning)
            .setContentTitle("Profile Load Failed")
            .setContentText("Your profile could not be loaded, you have been logged out.")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        with(NotificationManagerCompat.from(requireContext())) {
            notify(NOTIFICATION_ID, builder.build())
        }

        logoutUser()
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        getUserLocation() // Get user location after map is ready

        googleMap?.setOnMapClickListener { latLng ->
            if (currentUser != null) {
                val observation = hashMapOf(
                    "lat" to latLng.latitude,
                    "lng" to latLng.longitude,
                    "userId" to currentUser!!.uid
                )

                db.collection("observations")
                    .document(currentUser!!.uid)
                    .collection("userObservations")
                    .add(observation)
                    .addOnSuccessListener {
                        googleMap?.addMarker(
                            MarkerOptions()
                                .position(latLng)
                                .title("New Hotspot")
                        )
                        Toast.makeText(requireContext(), "Hotspot added at: ${latLng.latitude}, ${latLng.longitude}", Toast.LENGTH_SHORT).show()
                    }
                    .addOnFailureListener {
                        Toast.makeText(requireContext(), "Failed to add observation", Toast.LENGTH_SHORT).show()
                    }
            } else {
                Toast.makeText(requireContext(), "Please log in to add a hotspot", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        binding.mapView.onLowMemory()
    }
}
