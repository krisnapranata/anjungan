package com.anjungan.print

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.anjungan.print.databinding.ItemPoliBinding
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

class PoliAdapter(
    private val onSelect: (JSONObject) -> Unit
) : RecyclerView.Adapter<PoliAdapter.VH>() {

    var items: List<JSONObject> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    var now: Long = System.currentTimeMillis()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    class VH(val binding: ItemPoliBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemPoliBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val b = holder.binding
        val ctx = b.root.context

        b.tvPoliName.text = item.optString("nm_poli", "-")
        b.ivPoliIcon.setImageResource(iconFor(item.optString("nm_poli", "")))
        val jamMulai = item.optString("jam_mulai", "")
        b.tvPoliJam.text = if (jamMulai.isNotEmpty()) "Mulai $jamMulai"
        else item.optString("kd_poli", "")
        b.tvPoliRegistered.text = ctx.getString(
            R.string.label_terdaftar, item.optInt("jml_terdaftar", 0)
        )

        val state = stateFor(item)
        b.root.setBackgroundResource(state.bgRes)
        b.root.alpha = if (state.enabled) 1f else 0.65f
        b.tvPoliState.visibility = if (state.label == null) View.GONE else View.VISIBLE
        b.tvPoliState.text = state.label
        b.tvPoliState.setTextColor(ContextCompat.getColor(ctx, state.fgColor))
        b.viewPoliAccent.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(ctx, state.accentRes))
        b.root.isEnabled = state.enabled
        b.root.setOnClickListener {
            if (stateFor(item).enabled) onSelect(item)
        }
    }

    private fun iconFor(name: String): Int {
        val n = name.uppercase(Locale.getDefault())
        return when {
            n.contains("JANTUNG") -> R.drawable.ic_poli_jantung
            n.contains("ANAK") -> R.drawable.ic_poli_anak
            n.contains("GIGI") || n.contains("MULUT") -> R.drawable.ic_poli_gigi
            n.contains("MATA") -> R.drawable.ic_poli_mata
            n.contains("THT") || n.contains("TELINGA") -> R.drawable.ic_poli_tht
            n.contains("KANDUNGAN") || n.contains("KEBIDANAN") ||
                n.contains("OBGIN") || n.contains("BIDAN") -> R.drawable.ic_poli_kandungan
            n.contains("SARAF") -> R.drawable.ic_poli_saraf
            n.contains("PARU") -> R.drawable.ic_poli_paru
            n.contains("BEDAH") -> R.drawable.ic_poli_bedah
            n.contains("UMUM") -> R.drawable.ic_poli_umum
            n.contains("GIZI") -> R.drawable.ic_poli_gizi
            n.contains("LAB") -> R.drawable.ic_poli_lab
            n.contains("JIWA") || n.contains("PSIK") -> R.drawable.ic_poli_jiwa
            n.contains("DALAM") -> R.drawable.ic_poli_dalam
            else -> R.drawable.ic_poli_default
        }
    }

    private fun stateFor(item: JSONObject): State {
        val jamMulai = item.optString("jam_mulai", "")
        if (jamMulai.isEmpty()) {
            return State(
                R.drawable.bg_item, null, 0, true, R.color.blue_grad_start
            )
        }

        val parts = jamMulai.split(":")
        if (parts.size < 2) {
            return State(
                R.drawable.bg_item, null, 0, true, R.color.blue_grad_start
            )
        }
        val h = parts[0].toIntOrNull() ?: return State(
            R.drawable.bg_item, null, 0, true, R.color.blue_grad_start
        )
        val m = parts[1].toIntOrNull() ?: 0

        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, h)
        cal.set(Calendar.MINUTE, m)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val mulaiMs = cal.timeInMillis

        var tutupMs = Long.MAX_VALUE
        val jamSelesai = item.optString("jam_selesai", "")
        if (jamSelesai.isNotEmpty()) {
            val sp = jamSelesai.split(":")
            if (sp.size >= 2) {
                val sh = sp[0].toIntOrNull()
                val sm = sp[1].toIntOrNull()
                if (sh != null && sm != null) {
                    val c2 = Calendar.getInstance()
                    c2.set(Calendar.HOUR_OF_DAY, sh)
                    c2.set(Calendar.MINUTE, sm)
                    c2.set(Calendar.SECOND, 0)
                    c2.set(Calendar.MILLISECOND, 0)
                    tutupMs = c2.timeInMillis + 10_000
                }
            }
        }

        return when {
            now >= tutupMs -> State(
                R.drawable.bg_state_closed, "Tutup", R.color.gray_closed_fg,
                false, R.color.gray_closed_fg
            )
            now >= mulaiMs - 3_600_000 -> State(
                R.drawable.bg_state_green, "Buka", R.color.success,
                true, R.color.success
            )
            else -> {
                val diff = (mulaiMs - 3_600_000) - now
                val hh = diff / 3_600_000
                val mm = (diff % 3_600_000) / 60_000
                val ss = (diff % 60_000) / 1000
                val label = String.format(Locale.US, "%02d:%02d:%02d", hh, mm, ss)
                if (diff <= 3_600_000) {
                    State(
                        R.drawable.bg_state_yellow, label, R.color.alert_warning_fg,
                        false, R.color.warning
                    )
                } else {
                    State(
                        R.drawable.bg_state_red, label, R.color.alert_danger_fg,
                        false, R.color.danger
                    )
                }
            }
        }
    }

    private data class State(
        val bgRes: Int,
        val label: String?,
        val fgColor: Int,
        val enabled: Boolean,
        val accentRes: Int
    )
}
