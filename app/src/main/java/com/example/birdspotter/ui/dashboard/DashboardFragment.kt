package com.example.birdspotter.ui.dashboard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.birdspotter.R
import com.example.birdspotter.databinding.FragmentDashboardBinding
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore

class DashboardFragment : Fragment(), OnMapReadyCallback {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!

    private var googleMap: GoogleMap? = null
    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private var currentUser: FirebaseUser? = null

    private val birdObservations = mutableListOf<BirdObservation>()

    data class BirdObservation(val birdName: String, val notes: String, val location: String)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()
        currentUser = auth.currentUser

        setupToggleButtons()
        setupMapView(savedInstanceState)
        setupUserCard()

        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setupToggleButtons() {
        binding.userDetailsToggle.setOnClickListener {
            displayUserDetails()
        }

        binding.formDetailsToggle.setOnClickListener {
            displayFormDetails()
        }

        binding.birdObservationsToggle.setOnClickListener {
            displayBirdObservations()
        }
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
    }

    private fun setupUserCard() {
        if (currentUser == null) {
            displayLoginRegisterCard()
        } else {
            displayUserDetails()
        }
    }

    private fun displayLoginRegisterCard() {
        binding.sectionContent.removeAllViews()
        val userDetailsView = layoutInflater.inflate(R.layout.user_details_layout, binding.sectionContent, false)

        val loginButton = userDetailsView.findViewById<Button>(R.id.loginButton)
        val registerButton = userDetailsView.findViewById<Button>(R.id.registerButton)

        loginButton.setOnClickListener {
            Toast.makeText(requireContext(), "Navigate to Login", Toast.LENGTH_SHORT).show()
        }

        registerButton.setOnClickListener {
            Toast.makeText(requireContext(), "Navigate to Register", Toast.LENGTH_SHORT).show()
        }

        binding.sectionContent.addView(userDetailsView)
    }

    private fun displayUserDetails() {
        binding.sectionContent.removeAllViews()
        val userDetailsView = layoutInflater.inflate(R.layout.user_details_layout, binding.sectionContent, false)

        val userNameTextView = userDetailsView.findViewById<TextView>(R.id.userName)
        val birdWatcherTypeTextView = userDetailsView.findViewById<TextView>(R.id.userBirdWatcherType)
        val experienceTextView = userDetailsView.findViewById<TextView>(R.id.userBirdWatchingExperience)
        val profileImageView = userDetailsView.findViewById<ImageView>(R.id.profileImage)
        val logoutButton = userDetailsView.findViewById<Button>(R.id.logoutButton)

        currentUser?.let {
            userNameTextView.text = it.displayName ?: "Unknown User"
            birdWatcherTypeTextView.text = "Bird Watcher Type: Enthusiast"
            experienceTextView.text = "Experience: 3 years"

            it.photoUrl?.let { uri ->
                Glide.with(this).load(uri).into(profileImageView)
            }
        }

        logoutButton.setOnClickListener {
            auth.signOut()
            currentUser = null
            displayLoginRegisterCard()
            Toast.makeText(requireContext(), "Logged out successfully", Toast.LENGTH_SHORT).show()
        }

        binding.sectionContent.addView(userDetailsView)
    }

    private fun displayFormDetails() {
        binding.sectionContent.removeAllViews()
        val formDetailsView = layoutInflater.inflate(R.layout.fragment_add_bird, binding.sectionContent, false)

        val birdNameInput = formDetailsView.findViewById<EditText>(R.id.birdNameInput)
        val birdNotesInput = formDetailsView.findViewById<EditText>(R.id.birdNotesInput)
        val birdLocationInput = formDetailsView.findViewById<EditText>(R.id.birdLocationInput)
        val submitBirdButton = formDetailsView.findViewById<Button>(R.id.submitBirdButton)

        submitBirdButton.setOnClickListener {
            val birdName = birdNameInput.text.toString().trim()
            val birdNotes = birdNotesInput.text.toString().trim()
            val birdLocation = birdLocationInput.text.toString().trim()

            if (birdName.isNotEmpty() && birdLocation.isNotEmpty()) {
                addBirdObservation(birdName, birdNotes, birdLocation)
                Toast.makeText(requireContext(), "Bird observation submitted!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "Please fill in all required fields", Toast.LENGTH_SHORT).show()
            }
        }

        binding.sectionContent.addView(formDetailsView)
    }

    private fun addBirdObservation(birdName: String, notes: String, location: String) {
        val observation = BirdObservation(birdName, notes, location)
        currentUser?.let { user ->
            val observationRef = db.collection("users").document(user.uid).collection("birdObservations")
            observationRef.add(observation)
                .addOnSuccessListener {
                    birdObservations.add(observation)
                    Toast.makeText(requireContext(), "Observation added!", Toast.LENGTH_SHORT).show()
                }
                .addOnFailureListener {
                    Toast.makeText(requireContext(), "Error adding observation", Toast.LENGTH_SHORT).show()
                }
        }
    }

    private fun displayBirdObservations() {
        binding.sectionContent.removeAllViews()
        val birdObservationsView = layoutInflater.inflate(R.layout.user_bird_observations_layout, binding.sectionContent, false)
        val recyclerView = birdObservationsView.findViewById<RecyclerView>(R.id.birdObservationsRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = BirdObservationsAdapter(birdObservations)
        binding.sectionContent.addView(birdObservationsView)
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map

        val defaultLocation = LatLng(-34.0, 151.0)
        googleMap?.addMarker(MarkerOptions().position(defaultLocation).title("Default Location"))
        googleMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(defaultLocation, 10f))
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

    inner class BirdObservationsAdapter(private val observations: List<BirdObservation>) :
        RecyclerView.Adapter<BirdObservationsAdapter.ViewHolder>() {

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val birdName: TextView = itemView.findViewById(R.id.birdName)
            val birdLocation: TextView = itemView.findViewById(R.id.birdLocation)
            val birdNotes: TextView = itemView.findViewById(R.id.birdNotes)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_bird_observation, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val observation = observations[position]
            holder.birdName.text = observation.birdName
            holder.birdLocation.text = observation.location
            holder.birdNotes.text = observation.notes
        }

        override fun getItemCount(): Int = observations.size
    }
}
