package top.niunaijun.blackboxa.view.base

import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import top.niunaijun.blackboxa.runner.GlassBackground


open class BaseActivity : AppCompatActivity() {

    // Every manager screen gets the same live backdrop as the main screen, behind its content.
    override fun onContentChanged() {
        super.onContentChanged()
        try {
            val content = findViewById<ViewGroup>(android.R.id.content) ?: return
            if (content.childCount > 0 && content.getChildAt(0) is GlassBackground) {
                return
            }
            content.addView(
                    GlassBackground(this, true),
                    0,
                    ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                    )
            )
        } catch (e: Exception) {
            // the plain gradient from the theme is still there
        }
    }

    protected fun initToolbar(toolbar: Toolbar,title:Int, showBack: Boolean = false, onBack: (() -> Unit)? = null) {
        setSupportActionBar(toolbar)
        toolbar.setTitle(title)
        if (showBack) {
            supportActionBar?.let {
                it.setDisplayHomeAsUpEnabled(true)
                toolbar.setNavigationOnClickListener {
                    if (onBack != null) {
                        onBack()
                    }
                    finish()
                }
            }
        }
    }

    protected fun currentUserID():Int{
        return intent.getIntExtra("userID", 0)
    }
}
