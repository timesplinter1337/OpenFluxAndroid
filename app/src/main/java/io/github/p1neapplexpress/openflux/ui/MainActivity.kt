package io.github.p1neapplexpress.openflux.ui

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import io.github.p1neapplexpress.openflux.R
import io.github.p1neapplexpress.openflux.event.EventBus
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Go edge-to-edge and inflate first so the content root exists when the
        // window insets listener is attached below.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        supportActionBar?.hide()

        // Pad the real content root for the status/navigation bars and let the
        // insets keep propagating to children instead of consuming them.
        val root = findViewById<View>(R.id.main)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }

        // Only add the fragment on first creation; on recreation (e.g. rotation)
        // the framework restores it, so re-adding would stack duplicates.
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.main, MainFragment(), "")
                .commit()
        }

        lifecycleScope.launch {
            EventBus.events.collect { ev ->
                supportFragmentManager.fragments.forEach { f ->
                    if (f is BaseFragment) f.onNewEvent(ev)
                }
            }
        }
    }
}
