package top.niunaijun.blackboxa.view.main

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import top.niunaijun.blackboxa.util.InjectionUtil
import top.niunaijun.blackboxa.view.list.ListViewModel

class WelcomeActivity : AppCompatActivity() {

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (handleTestIntent(intent)) {
            return
        }
        jump()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (handleTestIntent(intent)) {
            return
        }
        previewInstalledAppList()
        jump()
    }

    private fun handleTestIntent(intent: Intent?): Boolean {
        if (!BlackBoxTestConfig.shouldRun(intent)) {
            return false
        }
        BlackBoxTestRunner.start(this, BlackBoxTestConfig.getTestPackage(intent))
        return true
    }

    private fun jump() {
        MainActivity.start(this)
        finish()
    }

    private fun previewInstalledAppList(){
        val viewModel = ViewModelProvider(this,InjectionUtil.getListFactory()).get(ListViewModel::class.java)
        viewModel.previewInstalledList()
    }
}
