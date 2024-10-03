package com.example.birdspotter.ui.notifications

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.birdspotter.R
import com.example.birdspotter.databinding.FragmentNotificationsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

data class NewsArticle(
    val title: String,
    val description: String?,
    val source: String,
    val imageUrl: String?
)

class NewsAdapter(private val articles: List<NewsArticle>) :
    RecyclerView.Adapter<NewsAdapter.NewsViewHolder>() {

    class NewsViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.title)
        val description: TextView = view.findViewById(R.id.description)
        val source: TextView = view.findViewById(R.id.source)
        val imageView: ImageView = view.findViewById(R.id.imageView)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NewsViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_observation, parent, false)
        return NewsViewHolder(view)
    }

    override fun onBindViewHolder(holder: NewsViewHolder, position: Int) {
        val article = articles[position]
        holder.title.text = article.title
        holder.description.text = article.description ?: "No description available."
        holder.source.text = article.source

        article.imageUrl?.let {
            Glide.with(holder.itemView.context)
                .load(it)
                .placeholder(R.drawable.placeholder_image)
                .into(holder.imageView)
        } ?: holder.imageView.setImageResource(R.drawable.placeholder_image)
    }

    override fun getItemCount(): Int = articles.size
}

class NotificationsFragment : Fragment() {

    private var _binding: FragmentNotificationsBinding? = null
    private val binding get() = _binding!!

    private val client = OkHttpClient()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        _binding = FragmentNotificationsBinding.inflate(inflater, container, false)
        val root: View = binding.root

        // Set up the RecyclerView
        binding.notificationsRecyclerView.layoutManager = LinearLayoutManager(requireContext())

        // Fetch and display news articles
        fetchNewsArticles()

        return root
    }

    private fun fetchNewsArticles() {
        lifecycleScope.launch(Dispatchers.IO) {
            val query = "bird spotting OR bird photography OR bird spot equipment"
            val url = "https://newsapi.org/v2/everything?q=$query&sortBy=publishedAt&apiKey=b6412a72648641c5a8f74273286ebdd2"

            val request = Request.Builder().url(url).build()

            try {
                val response = client.newCall(request).execute()
                val responseData = response.body?.string()

                if (response.isSuccessful && !responseData.isNullOrEmpty()) {
                    val articles = parseNewsArticles(responseData)
                    withContext(Dispatchers.Main) {
                        // Only update the UI if the binding is still valid
                        if (isAdded && _binding != null) {
                            binding.notificationsRecyclerView.adapter = NewsAdapter(articles)
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        showError("Failed to fetch news articles.")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showError("Error: ${e.message}")
                }
            }
        }
    }

    private fun parseNewsArticles(responseData: String): List<NewsArticle> {
        val articles = mutableListOf<NewsArticle>()
        val jsonObj = JSONObject(responseData)
        val jsonArray = jsonObj.getJSONArray("articles")

        for (i in 0 until jsonArray.length()) {
            val articleObj = jsonArray.getJSONObject(i)
            val title = articleObj.getString("title")
            val description = articleObj.optString("description", null)
            val source = articleObj.getJSONObject("source").getString("name")
            val imageUrl = articleObj.optString("urlToImage", null)

            articles.add(NewsArticle(title, description, source, imageUrl))
        }
        return articles
    }

    private fun showError(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
