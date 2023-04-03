package com.flipcam

import android.graphics.Color
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import io.agora.agorauikit_android.*
import io.agora.rtc2.Constants
import java.util.logging.Level
import java.util.logging.Logger

class TestActivity : AppCompatActivity() {
    var agView: AgoraVideoViewer? = null
    var channel = "test"
    var token = ""

    @OptIn(ExperimentalUnsignedTypes::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_test)

        try {
            agView = AgoraVideoViewer(
                this, AgoraConnectionData("67394c697e8a4fe9b8f149733eeeea32"),
                agoraSettings = this.settingsWithExtraButtons()
            )
        } catch (e: Exception) {
            println("Could not initialise AgoraVideoViewer. Check your App ID is valid. ${e.message}")
            return
        }
        val set = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )

        this.addContentView(agView, set)

        println("agView!!.isInRtcChannel =  ${agView!!.isInRtcChannel}")
        // val randomUID = Random.nextInt(0, 100)
        val randomUID = 1


        try {
            // Check that the camera and mic permissions are accepted before attempting to join
            if (AgoraVideoViewer.requestPermission(this)) {
                agView!!.join(this.channel, fetchToken = true, role = Constants.CLIENT_ROLE_BROADCASTER, uid = randomUID)
            } else {
                val joinButton = Button(this)
                joinButton.text = "Allow Camera and Microphone, then click here"
                joinButton.setOnClickListener {
                    // When the button is clicked, check permissions again and join channel
                    // if permissions are granted.
                    if (AgoraVideoViewer.requestPermission(this)) {
                        (joinButton.parent as ViewGroup).removeView(joinButton)
                        agView!!.join(this.channel, fetchToken = true, role = Constants.CLIENT_ROLE_BROADCASTER, uid = randomUID)
                    }
                }
                joinButton.setBackgroundColor(Color.GREEN)
                joinButton.setTextColor(Color.RED)
                this.addContentView(
                    joinButton,
                    FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 300)
                )
            }
        } catch (e: Exception) {
            println("Exception in AgoraVideoViewer. ${e.message}")
            return
        }

    }

    fun settingsWithExtraButtons(): AgoraSettings {
        val agoraSettings = AgoraSettings()

        agoraSettings.tokenURL = "https://agora-token-service.sitebill.site"

        val agBeautyButton = AgoraButton(this)
        agBeautyButton.clickAction = {
            Logger.getLogger("AgoraVideoUIKit").log(Level.WARNING, "click button sitebill.site")

            println("sitebill.site leave channel2")

            it.isSelected = !it.isSelected
            agBeautyButton.setImageResource(
                if (it.isSelected) android.R.drawable.star_on else android.R.drawable.star_off
            )
            it.background.setTint(if (it.isSelected) Color.GREEN else Color.GRAY)
            this.agView?.agkit?.setBeautyEffectOptions(it.isSelected, this.agView?.beautyOptions)
        }
        agBeautyButton.setImageResource(android.R.drawable.star_off)

        agoraSettings.extraButtons = mutableListOf(agBeautyButton)

        return agoraSettings
    }

}