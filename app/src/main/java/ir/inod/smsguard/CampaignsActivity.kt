package ir.inod.smsguard

import android.os.Bundle
import android.view.MenuItem
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.card.MaterialCardView

/** Human-readable view of locally detected near-duplicate SMS campaigns. */
class CampaignsActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(androidx.core.content.ContextCompat.getColor(this@CampaignsActivity, R.color.screen_bg))
        }
        val toolbar = com.google.android.material.appbar.MaterialToolbar(this).apply {
            title = getString(R.string.campaigns)
            setNavigationIcon(android.R.drawable.ic_media_previous)
            setNavigationOnClickListener { finish() }
        }
        root.addView(toolbar, LinearLayout.LayoutParams(-1, resources.getDimensionPixelSize(R.dimen.appbar_height)))
        val scroll = android.widget.ScrollView(this)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (16 * resources.displayMetrics.density).toInt(); setPadding(p, p, p, p)
        }
        val campaigns = CampaignStore(this).allCampaigns()
        if (campaigns.isEmpty()) list.addView(TextView(this).apply {
            text = getString(R.string.no_campaigns); textSize = 16f
        })
        campaigns.forEach { campaign ->
            val card = MaterialCardView(this).apply {
                radius = 16 * resources.displayMetrics.density
                setCardBackgroundColor(androidx.core.content.ContextCompat.getColor(this@CampaignsActivity, R.color.card_bg))
            }
            val text = TextView(this).apply {
                val status = if (campaign.spamFlagged) getString(R.string.campaign_spam) else getString(R.string.campaign_detected)
                this.text = getString(R.string.campaign_summary, campaign.members, campaign.senders.size, status)
                textSize = 15f
                val p = (16 * resources.displayMetrics.density).toInt(); setPadding(p, p, p, p)
            }
            card.addView(text); list.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = (10 * resources.displayMetrics.density).toInt()
            })
        }
        scroll.addView(list); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f)); setContentView(root)
    }
    override fun onOptionsItemSelected(item: MenuItem): Boolean = if (item.itemId == android.R.id.home) { finish(); true } else super.onOptionsItemSelected(item)
}
