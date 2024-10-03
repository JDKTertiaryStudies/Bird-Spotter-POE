package com.example.birdspotter.ui.dashboard

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.example.birdspotter.R
import com.example.birdspotter.databinding.FragmentDashboardBinding
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.model.LatLng
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*

class DashboardFragment : Fragment(), OnMapReadyCallback {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private lateinit var storageRef: StorageReference
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var adapter: BirdObservationAdapter
    private lateinit var hotspotAdapter: HotspotAdapter

    private var birdImageUri: Uri? = null
    private var googleMap: GoogleMap? = null

    private val hotspotList = mutableListOf<Hotspot>()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)

        setupFirebase()
        setupUI()
        setupMapView(savedInstanceState)

        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setupFirebase() {
        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()
        storageRef = FirebaseStorage.getInstance().reference
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity())
    }

    private fun setupUI() {
        setupRecyclerView()
        setupHotspotRecyclerView()
        setupSearchBar()

        // Add button click listeners
        binding.signOutButton.setOnClickListener { performSignOut() }
        binding.addBirdButton.setOnClickListener { showAddBirdForm() }

        binding.loginButton.setOnClickListener {
            val email = binding.emailInput.text.toString().trim()
            val password = binding.passwordInput.text.toString().trim()

            if (email.isNotEmpty() && password.isNotEmpty()) {
                auth.signInWithEmailAndPassword(email, password)
                    .addOnCompleteListener { task ->
                        if (task.isSuccessful) {
                            Toast.makeText(context, "Login Successful", Toast.LENGTH_SHORT).show()
                            setupDashboard()  // Call to switch to dashboard if login is successful
                        } else {
                            Toast.makeText(context, "Login Failed: ${task.exception?.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
            } else {
                Toast.makeText(context, "Please fill in all fields", Toast.LENGTH_SHORT).show()
            }
        }

        binding.registerButton.setOnClickListener {
            val email = binding.emailInput.text.toString().trim()
            val password = binding.passwordInput.text.toString().trim()

            if (email.isNotEmpty() && password.isNotEmpty()) {
                auth.createUserWithEmailAndPassword(email, password)
                    .addOnCompleteListener { task ->
                        if (task.isSuccessful) {
                            val user = auth.currentUser
                            user?.sendEmailVerification()?.addOnCompleteListener { verifyTask ->
                                if (verifyTask.isSuccessful) {
                                    Toast.makeText(context, "Registration Successful. Please verify your email.", Toast.LENGTH_SHORT).show()
                                    auth.signOut()  // Sign out the user to wait for email verification
                                } else {
                                    Toast.makeText(context, "Verification email not sent: ${verifyTask.exception?.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        } else {
                            Toast.makeText(context, "Registration Failed: ${task.exception?.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
            } else {
                Toast.makeText(context, "Please fill in all fields", Toast.LENGTH_SHORT).show()
            }
        }


        checkAuthentication()
    }

    private fun setupRecyclerView() {
        adapter = BirdObservationAdapter { observationId -> deleteBirdObservation(observationId) }
        binding.observationList.layoutManager = LinearLayoutManager(requireContext())
        binding.observationList.adapter = adapter
    }

    private fun setupHotspotRecyclerView() {
        hotspotAdapter = HotspotAdapter(hotspotList)
        binding.hotspotRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.hotspotRecyclerView.adapter = hotspotAdapter
    }

    private fun setupSearchBar() {
        binding.locationSearchInput.setOnEditorActionListener { v, _, _ ->
            val locationName = v.text.toString()
            if (locationName.isNotEmpty()) {
                moveToLocation(locationName)
            }
            false
        }
    }

    private fun setupMapView(savedInstanceState: Bundle?) {
        binding.mapView.onCreate(savedInstanceState)
        binding.mapView.getMapAsync(this)
    }

    private fun checkAuthentication() {
        auth.currentUser?.let {
            if (it.isEmailVerified) {
                loadUserData(it)  // Load user data from Firestore
                setupDashboard()
            } else {
                Toast.makeText(context, "Please verify your email.", Toast.LENGTH_SHORT).show()
                auth.signOut()
                showAuthLayout()
            }
        } ?: run {
            showAuthLayout()
        }
    }

    private fun loadUserData(user: FirebaseUser) {
        db.collection("users").document(user.uid).get()
            .addOnSuccessListener { document ->
                if (document.exists()) {
                    // Assuming user data has fields: name, birdWatcherType, birdWatchingDuration
                    binding.userName.text = document.getString("name")
                    binding.userBirdWatcherType.text = document.getString("birdWatcherType")
                    binding.userBirdWatchingDuration.text = document.getString("birdWatchingDuration")

                    getCurrentLocation()  // Also get and display the user's current location
                } else {
                    Toast.makeText(context, "User data not found", Toast.LENGTH_SHORT).show()
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(context, "Failed to load user data: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun setupDashboard() {
        loadBirdObservations()
        loadHotspotsFromFirebase()
        showDashboardLayout()  // Ensure this is called to show the correct layout
    }

    private fun loadBirdObservations() {
        auth.currentUser?.let { user ->
            db.collection("users").document(user.uid).collection("birdObservations")
                .get()
                .addOnSuccessListener { result ->
                    val observations = result.documents.mapNotNull {
                        it.toObject(BirdObservation::class.java)?.apply { id = it.id }
                    }
                    adapter.submitList(observations)
                }
                .addOnFailureListener { exception ->
                    Toast.makeText(context, "Failed to load observations: ${exception.message}", Toast.LENGTH_SHORT).show()
                }
        }
    }

    private fun performSignOut() {
        auth.signOut()
        showLoginLayout()  // Show the login layout when signing out
        Toast.makeText(context, "Signed out successfully", Toast.LENGTH_SHORT).show()
    }

    @SuppressLint("SetTextI18n")
    private fun getCurrentLocation() {
        if (ActivityCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(requireActivity(), arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
            return
        }
        fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
            if (location != null) {
                binding.userCoordinates.text = "Lat: ${location.latitude}, Lon: ${location.longitude}"
                fetchLocationDetails(location)
            } else {
                binding.userLocation.text = "Location Not available"
                binding.userCoordinates.text = "Coordinates Not available"
            }
        }.addOnFailureListener { e ->
            Toast.makeText(context, "Failed to get location: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("SetTextI18n")
    private fun fetchLocationDetails(location: Location) {
        val geocoder = Geocoder(requireContext(), Locale.getDefault())
        val addresses = geocoder.getFromLocation(location.latitude, location.longitude, 1)
        if (addresses!!.isNotEmpty()) {
            val address = addresses[0]
            val area = address.subLocality ?: "N/A"
            val city = address.locality ?: "N/A"
            val country = address.countryName ?: "N/A"
            binding.userLocation.text = "$area, $city, $country"
        } else {
            binding.userLocation.text = "Not available"
        }
    }

    private fun showAddBirdForm() {
        // Inflating the add bird form layout
        val inflater = LayoutInflater.from(context)
        val addBirdView = inflater.inflate(R.layout.fragment_add_bird, binding.dashboardLayout, false)

        // Replace the dashboard layout with the add bird form
        binding.dashboardLayout.removeAllViews()
        binding.dashboardLayout.addView(addBirdView)

        val birdNameInput: EditText = addBirdView.findViewById(R.id.birdNameInput)
        val birdNotesInput: EditText = addBirdView.findViewById(R.id.birdNotesInput)
        val birdLocationInput: EditText = addBirdView.findViewById(R.id.birdLocationInput)
        val birdImageView: ImageView = addBirdView.findViewById(R.id.birdImageView)
        val selectImageButton: Button = addBirdView.findViewById(R.id.selectImageButton)
        val submitBirdButton: Button = addBirdView.findViewById(R.id.submitBirdButton)
        val cancelButton: Button = addBirdView.findViewById(R.id.cancelButton)

        selectImageButton.setOnClickListener { selectImage() }
        submitBirdButton.setOnClickListener {
            val birdName = birdNameInput.text.toString().trim()
            val birdNotes = birdNotesInput.text.toString().trim()
            val birdLocation = birdLocationInput.text.toString().trim()

            if (validateInputs(birdName, birdNotes, birdLocation)) {
                if (birdImageUri != null) {
                    uploadImageAndSaveObservation(birdName, birdNotes, birdLocation)
                } else {
                    addNewBirdObservation(birdName, birdNotes, birdLocation, null)
                }
            } else {
                Toast.makeText(context, "Please fill in all fields", Toast.LENGTH_SHORT).show()
            }
        }
        cancelButton.setOnClickListener {
            showDashboardLayout()  // Go back to the dashboard
        }
    }

    // Showing layouts based on the user's authentication state
    private fun showAuthLayout() {
        binding.authLayout.visibility = View.VISIBLE
        binding.dashboardLayout.visibility = View.GONE
    }

    private fun showDashboardLayout() {
        binding.authLayout.visibility = View.GONE
        binding.dashboardLayout.visibility = View.VISIBLE
    }

    private fun showLoginLayout() {
        binding.authLayout.visibility = View.VISIBLE
        binding.dashboardLayout.visibility = View.GONE
        binding.emailInput.text.clear()
        binding.passwordInput.text.clear()
        binding.emailInput.requestFocus()
    }

    private fun selectImage() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        imagePickerLauncher.launch(intent)
    }

    private val imagePickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                birdImageUri = result.data?.data
                Glide.with(this)
                    .load(birdImageUri)
                    .override(200, 200)
                    .centerCrop()
                    .into(binding.profileImage)
            }
        }

    private fun uploadImageAndSaveObservation(birdName: String, birdNotes: String, birdLocation: String) {
        val imageRef = storageRef.child("bird_images/${UUID.randomUUID()}.jpg")
        birdImageUri?.let { uri ->
            imageRef.putFile(uri)
                .addOnSuccessListener {
                    imageRef.downloadUrl.addOnSuccessListener { downloadUrl ->
                        addNewBirdObservation(birdName, birdNotes, birdLocation, downloadUrl.toString())
                    }
                }
                .addOnFailureListener {
                    Toast.makeText(context, "Image upload failed: ${it.message}", Toast.LENGTH_SHORT).show()
                }
        }
    }

    private fun addNewBirdObservation(birdName: String, birdNotes: String, birdLocation: String, imageUrl: String?) {
        val birdData = BirdObservation(birdName, birdNotes, birdLocation, imageUrl)
        auth.currentUser?.let { user ->
            db.collection("users").document(user.uid).collection("birdObservations").add(birdData)
                .addOnSuccessListener {
                    Toast.makeText(context, "Bird observation added!", Toast.LENGTH_SHORT).show()
                    loadBirdObservations()
                    showDashboardLayout()
                }
                .addOnFailureListener { e ->
                    Toast.makeText(context, "Failed to add observation: ${e.message}", Toast.LENGTH_SHORT).show()
                }
        }
    }

    private fun deleteBirdObservation(observationId: String) {
        auth.currentUser?.let { user ->
            db.collection("users").document(user.uid).collection("birdObservations")
                .document(observationId)
                .delete()
                .addOnSuccessListener {
                    Toast.makeText(context, "Bird observation deleted!", Toast.LENGTH_SHORT).show()
                    loadBirdObservations()
                }
                .addOnFailureListener { e ->
                    Toast.makeText(context, "Failed to delete observation: ${e.message}", Toast.LENGTH_SHORT).show()
                }
        }
    }

    private fun validateInputs(vararg inputs: String): Boolean {
        return inputs.all { it.isNotEmpty() }
    }

    private fun loadHotspotsFromFirebase() {
        auth.currentUser?.let { user ->
            db.collection("users").document(user.uid).collection("hotspots")
                .get()
                .addOnSuccessListener { result ->
                    hotspotList.clear()
                    for (document in result) {
                        val hotspot = document.toObject(Hotspot::class.java)
                        val latLng = LatLng(hotspot.latitude, hotspot.longitude)
                        val marker = googleMap?.addMarker(
                            com.google.android.gms.maps.model.MarkerOptions()
                                .position(latLng)
                                .title(hotspot.locationName)
                        )
                        hotspot.markerId = marker?.id ?: ""
                        hotspotList.add(hotspot)
                    }
                    hotspotAdapter.notifyDataSetChanged()
                }
                .addOnFailureListener { e ->
                    Toast.makeText(context, "Failed to load hotspots: ${e.message}", Toast.LENGTH_SHORT).show()
                }
        }
    }

    private fun moveToLocation(locationName: String) {
        lifecycleScope.launch {
            val geocoder = Geocoder(requireContext(), Locale.getDefault())
            val addresses = withContext(Dispatchers.IO) {
                geocoder.getFromLocationName(locationName, 1)
            }
            if (addresses != null && addresses.isNotEmpty()) {
                val address = addresses[0]
                val latLng = LatLng(address.latitude, address.longitude)
                googleMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, 12f))
            } else {
                Toast.makeText(context, "Location not found.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        loadHotspotsFromFirebase()
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

    data class BirdObservation(
        val birdName: String = "",
        val notes: String = "",
        val location: String = "",
        val imageUrl: String? = null,
        var id: String = ""
    )

    data class Hotspot(
        val latitude: Double = 0.0,
        val longitude: Double = 0.0,
        var markerId: String = "",
        val locationName: String = "Unknown Location"
    )

    class BirdObservationAdapter(
        private val onDeleteClick: (String) -> Unit
    ) : androidx.recyclerview.widget.ListAdapter<BirdObservation, BirdObservationAdapter.BirdObservationViewHolder>(BirdObservationDiffCallback()) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BirdObservationViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.user_bird_observation, parent, false)
            return BirdObservationViewHolder(view, onDeleteClick)
        }

        override fun onBindViewHolder(holder: BirdObservationViewHolder, position: Int) {
            holder.bind(getItem(position))
        }

        class BirdObservationViewHolder(
            itemView: View,
            private val onDeleteClick: (String) -> Unit
        ) : androidx.recyclerview.widget.RecyclerView.ViewHolder(itemView) {
            private val birdName: TextView = itemView.findViewById(R.id.birdName)
            private val notes: TextView = itemView.findViewById(R.id.notes)
            private val location: TextView = itemView.findViewById(R.id.location)
            private val birdImageView: ImageView = itemView.findViewById(R.id.birdImageView)
            private val deleteIcon: ImageView = itemView.findViewById(R.id.deleteIcon)

            fun bind(observation: BirdObservation) {
                birdName.text = observation.birdName
                notes.text = observation.notes
                location.text = observation.location
                birdImageView.visibility = if (observation.imageUrl != null) View.VISIBLE else View.GONE

                observation.imageUrl?.let {
                    Glide.with(itemView.context)
                        .load(it)
                        .into(birdImageView)
                }

                deleteIcon.setOnClickListener {
                    onDeleteClick(observation.id)
                }
            }
        }

        class BirdObservationDiffCallback : androidx.recyclerview.widget.DiffUtil.ItemCallback<BirdObservation>() {
            override fun areItemsTheSame(oldItem: BirdObservation, newItem: BirdObservation): Boolean {
                return oldItem.id == newItem.id
            }

            override fun areContentsTheSame(oldItem: BirdObservation, newItem: BirdObservation): Boolean {
                return oldItem == newItem
            }
        }
    }

    class HotspotAdapter(
        private val hotspots: MutableList<Hotspot>
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<HotspotAdapter.HotspotViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HotspotViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_fav_hotspot, parent, false)
            return HotspotViewHolder(view)
        }

        override fun onBindViewHolder(holder: HotspotViewHolder, position: Int) {
            val hotspot = hotspots[position]
            holder.bind(hotspot)
        }

        override fun getItemCount(): Int = hotspots.size

        class HotspotViewHolder(itemView: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(itemView) {
            private val hotspotName: TextView = itemView.findViewById(R.id.hotspotName)
            private val hotspotCoordinates: TextView = itemView.findViewById(R.id.hotspotCoordinates)

            fun bind(hotspot: Hotspot) {
                hotspotName.text = hotspot.locationName
                hotspotCoordinates.text = "Lat: ${hotspot.latitude}, Lon: ${hotspot.longitude}"
            }
        }
    }
}
