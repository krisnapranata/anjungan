package com.anjungan.print

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.anjungan.print.databinding.ItemDokterBinding
import org.json.JSONObject

class DokterAdapter(
    private val onSelect: (JSONObject) -> Unit
) : RecyclerView.Adapter<DokterAdapter.VH>() {

    var items: List<JSONObject> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    class VH(val binding: ItemDokterBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemDokterBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val b = holder.binding
        val ctx = b.root.context

        b.tvDokterName.text = item.optString("nm_dokter", "-")
        val jamMulai = item.optString("jam_mulai", "")
        val jamSelesai = item.optString("jam_selesai", "")
        b.tvDokterJam.text = if (jamMulai.isNotEmpty() && jamSelesai.isNotEmpty()) {
            "$jamMulai - $jamSelesai"
        } else {
            item.optString("kd_dokter", "")
        }

        val sisa = item.optInt("sisa", 0)
        if (sisa > 0) {
            b.tvDokterKuota.text = ctx.getString(
                R.string.label_kuota, sisa, item.optInt("kuota", 0)
            )
            b.tvDokterKuota.setTextColor(ContextCompat.getColor(ctx, R.color.blue_grad_end))
            b.root.alpha = 1f
            b.root.setOnClickListener { onSelect(item) }
        } else {
            b.tvDokterKuota.text = ctx.getString(R.string.label_penuh)
            b.tvDokterKuota.setTextColor(ContextCompat.getColor(ctx, R.color.muted))
            b.root.alpha = 0.5f
            b.root.setOnClickListener(null)
        }
    }
}
