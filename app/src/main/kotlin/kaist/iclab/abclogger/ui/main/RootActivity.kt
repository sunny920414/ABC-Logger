package kaist.iclab.abclogger.ui.main

import android.os.Bundle
import com.google.firebase.auth.FirebaseAuth
import kaist.iclab.abclogger.base.BaseAppCompatActivity
import kaist.iclab.abclogger.ui.main.MainActivity
import kaist.iclab.abclogger.ui.main.SignInActivity

class RootActivity: BaseAppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val currentUser = FirebaseAuth.getInstance().currentUser

        if (currentUser != null) {
            startActivity(MainActivity.newIntent(this))
            finish()
        } else {
            startActivity(SignInActivity.newIntent(this))
            finish()
        }
    }
}