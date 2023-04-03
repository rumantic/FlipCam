package io.agora.agorauikit_android

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.PopupMenu
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.RecyclerView
import io.agora.agorauikit_android.AgoraRtmController.*
import io.agora.base.NV21Buffer
import io.agora.base.VideoFrame
import io.agora.base.internal.video.YuvHelper
import io.agora.rtc2.Constants
import io.agora.rtc2.IRtcEngineEventHandler
import io.agora.rtc2.RtcEngine
import io.agora.rtc2.RtcEngineConfig
import io.agora.rtc2.video.BeautyOptions
import io.agora.rtc2.video.IVideoFrameObserver
import io.agora.rtc2.video.VideoEncoderConfiguration
import io.agora.rtm.RtmChannel
import io.agora.rtm.RtmClient
import java.io.*
import java.nio.ByteBuffer
import java.util.logging.Level
import java.util.logging.Logger


/**
 * An interface for getting some common delegate callbacks without needing to subclass.
 */
interface AgoraVideoViewerDelegate {
    /**
     * Local user has joined a channel
     * @param channel Channel that the local user has joined.
     */
    fun joinedChannel(channel: String) {}

    /**
     * Local user has left a channel
     * @param channel Channel that the local user has left.
     */
    fun leftChannel(channel: String) {}

    /**
     * The token used to connect to the current active channel will expire in 30 seconds.
     * @param token Token that is currently used to connect to the channel.
     * @return Return true if the token fetch is being handled by this method.
     */
    fun tokenWillExpire(token: String?): Boolean {
        return false
    }

    /**
     * The token used to connect to the current active channel has expired.
     * @return Return true if the token fetch is being handled by this method.
     */
    fun tokenDidExpire(): Boolean {
        return false
    }
}

@ExperimentalUnsignedTypes

/**
 * View to contain all the video session objects, including camera feeds and buttons for settings
 */
open class AgoraVideoViewer : FrameLayout {

    /**
     * Style and organisation to be applied to all the videos in this view.
     */
    enum class Style {
        GRID, FLOATING, COLLECTION
    }

    /**
     * Gets and sets the role for the user. Either `.audience` or `.broadcaster`.
     */
    var userRole: Int = Constants.CLIENT_ROLE_BROADCASTER
        set(value: Int) {
            field = value
            this.agkit.setClientRole(value)
        }

    internal var controlContainer: ButtonContainer? = null
    internal var camButton: AgoraButton? = null
    internal var micButton: AgoraButton? = null
    internal var flipButton: AgoraButton? = null
    internal var endCallButton: AgoraButton? = null
    internal var screenShareButton: AgoraButton? = null

    companion object {}

    internal var remoteUserIDs: MutableSet<Int> = mutableSetOf()
    internal var userVideoLookup: MutableMap<Int, AgoraSingleVideoView> = mutableMapOf()
    internal val userVideosForGrid: Map<Int, AgoraSingleVideoView>
        get() {
            return if (this.style == Style.FLOATING) {
                this.userVideoLookup.filterKeys {
                    it == (this.overrideActiveSpeaker ?: this.activeSpeaker ?: this.userID)
                }
            } else if (this.style == Style.GRID) {
                this.userVideoLookup
            } else {
                emptyMap()
            }
        }

    /**
     * Default beautification settings
     */
    open val beautyOptions: BeautyOptions
        get() {
            val beautyOptions = BeautyOptions()
            beautyOptions.smoothnessLevel = 1f
            beautyOptions.rednessLevel = 0.1f
            return beautyOptions
        }

    /**
     * Video views to be displayed in the floating collection view.
     */
    val collectionViewVideos: Map<Int, AgoraSingleVideoView>
        get() {
            return if (this.style == Style.FLOATING) {
                return this.userVideoLookup
            } else {
                emptyMap()
            }
        }

    /**
     * ID of the local user.
     * Setting to zero will tell Agora to assign one for you once connected.
     */
    public var userID: Int = 0
        internal set

    /**
     * A boolean to check whether the user has joined the RTC channel or not.
     */
    var isInRtcChannel: Boolean? = false

    /**
     * The most recently active speaker in the session.
     * This will only ever be set to remote users, not the local user.
     */
    public var activeSpeaker: Int? = null
        internal set
    private val newHandler = AgoraVideoViewerHandler(this)
    internal val agoraRtmClientHandler = AgoraRtmClientHandler(this)
    internal val agoraRtmChannelHandler = AgoraRtmChannelHandler(this)

    var rtcOverrideHandler: IRtcEngineEventHandler? = null
    var rtmClientOverrideHandler: AgoraRtmClientHandler? = null
    var rtmChannelOverrideHandler: AgoraRtmChannelHandler? = null

    internal fun addUserVideo(userId: Int): AgoraSingleVideoView {
        this.userVideoLookup[userId]?.let { remoteView ->
            return remoteView
        }
        val remoteVideoView =
            AgoraSingleVideoView(this.context, userId, this.agoraSettings.colors.micFlag)
        remoteVideoView.canvas.renderMode = this.agoraSettings.videoRenderMode
        this.agkit.setupRemoteVideo(remoteVideoView.canvas)
//        this.agkit.setRemoteVideoRenderer(remoteVideoView.uid, remoteVideoView.textureView)
        this.userVideoLookup[userId] = remoteVideoView

        var hostControl: ImageView = ImageView(this.context)
        val density = Resources.getSystem().displayMetrics.density
        val hostControlLayout = FrameLayout.LayoutParams(40 * density.toInt(), 40 * density.toInt())
        hostControlLayout.gravity = Gravity.END

        hostControl = ImageView(this.context)
        hostControl.setImageResource(R.drawable.ic_round_pending_24)
        hostControl.setColorFilter(Color.WHITE)
        hostControl.setOnClickListener {
            val menu = PopupMenu(this.context, remoteVideoView)

            menu.menu.apply {
                add("Request user to " + (if (remoteVideoView.audioMuted) "un" else "") + "mute the mic").setOnMenuItemClickListener {
                    AgoraRtmController.Companion.sendMuteRequest(
                        peerRtcId = userId,
                        mute = !remoteVideoView.audioMuted,
                        hostView = this@AgoraVideoViewer,
                        deviceType = DeviceType.MIC
                    )
                    true
                }
                add("Request user to " + (if (remoteVideoView.videoMuted) "en" else "dis") + "able the camera").setOnMenuItemClickListener {
                    AgoraRtmController.Companion.sendMuteRequest(
                        peerRtcId = userId,
                        mute = !remoteVideoView.videoMuted,
                        hostView = this@AgoraVideoViewer,
                        deviceType = DeviceType.CAMERA
                    )
                    true
                }
            }
            menu.show()
        }
        if (agoraSettings.rtmEnabled) {
            remoteVideoView.addView(hostControl, hostControlLayout)
        }

        if (this.activeSpeaker == null) {
            this.activeSpeaker = userId
        }
        this.reorganiseVideos()
        return remoteVideoView
    }

    internal fun removeUserVideo(uid: Int, reogranise: Boolean = true) {
        val userSingleView = this.userVideoLookup[uid] ?: return
//        val canView = userSingleView.hostingView ?: return
        this.agkit.muteRemoteVideoStream(uid, true)
        userSingleView.canvas.view = null
        this.userVideoLookup.remove(uid)

        this.activeSpeaker.let {
            if (it == uid) this.setRandomSpeaker()
        }
        if (reogranise) {
            this.reorganiseVideos()
        }
    }

    internal fun setRandomSpeaker() {
        this.activeSpeaker = this.userVideoLookup.keys.shuffled().firstOrNull { it != this.userID }
    }

    /**
     * Active speaker override.
     */
    public var overrideActiveSpeaker: Int? = null
        set(newValue) {
            val oldValue = this.overrideActiveSpeaker
            field = newValue
            if (field != oldValue) {
                this.reorganiseVideos()
            }
        }

    internal fun addLocalVideo(): AgoraSingleVideoView? {
        if (this.userID == 0 || this.userVideoLookup.containsKey(this.userID)) {
            return this.userVideoLookup[this.userID]
        }
        this.agkit.enableVideo()
        this.agkit.startPreview()
        val vidView = AgoraSingleVideoView(this.context, 0, this.agoraSettings.colors.micFlag)
        vidView.canvas.renderMode = this.agoraSettings.videoRenderMode
        this.agkit.enableVideo()
        this.agkit.setupLocalVideo(vidView.canvas)
        this.agkit.startPreview()
        this.userVideoLookup[this.userID] = vidView
        this.reorganiseVideos()
        return vidView
    }

    internal var connectionData: AgoraConnectionData

    /**
     * Creates an AgoraVideoViewer object, to be placed anywhere in your application.
     * @param context: Application context
     * @param connectionData: Storing struct for holding data about the connection to Agora service.
     * @param style: Style and organisation to be applied to all the videos in this AgoraVideoViewer.
     * @param agoraSettings: Settings for this viewer. This can include style customisations and information of where to get new tokens from.
     * @param delegate: Delegate for the AgoraVideoViewer, used for some important callback methods.
     */
    @Throws(Exception::class)
    @JvmOverloads public constructor(
        context: Context,
        connectionData: AgoraConnectionData,
        style: Style = Style.FLOATING,
        agoraSettings: AgoraSettings = AgoraSettings(),
        delegate: AgoraVideoViewerDelegate? = null
    ) : super(context) {
        this.connectionData = connectionData
        this.style = style
        this.agoraSettings = agoraSettings
        this.delegate = delegate
//        this.setBackgroundColor(Color.BLUE)
        initAgoraEngine()
        this.addView(
            this.backgroundVideoHolder,
            ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.MATCH_PARENT,
                ConstraintLayout.LayoutParams.MATCH_PARENT
            )
        )
        this.addView(
            this.floatingVideoHolder,
            ConstraintLayout.LayoutParams(ConstraintLayout.LayoutParams.MATCH_PARENT, 200)
        )
        this.floatingVideoHolder.setBackgroundColor(this.agoraSettings.colors.floatingBackgroundColor)
        this.floatingVideoHolder.background.alpha =
            this.agoraSettings.colors.floatingBackgroundAlpha
    }

    val agoraRtmController = AgoraRtmController(this)


    @Throws(Exception::class)
    private fun initAgoraEngine() {
        if (connectionData.appId == "my-app-id") {
            Logger.getLogger("AgoraVideoUIKit").log(Level.SEVERE, "Change the App ID!")
            throw IllegalArgumentException("Change the App ID!")
        }
        val rtcEngineConfig = RtcEngineConfig()
        rtcEngineConfig.mAppId = connectionData.appId
        rtcEngineConfig.mContext = context.applicationContext
        rtcEngineConfig.mEventHandler = this.newHandler
        rtcEngineConfig.mLogConfig.level = 0x0000

        try {
            this.agkit = RtcEngine.create(rtcEngineConfig)
        } catch (e: Exception) {
            println("Exception while initializing the SDK : ${e.message}")
        }

        agkit.setParameters("{\"rtc.using_ui_kit\": 1}")
        agkit.enableAudioVolumeIndication(1000, 3, true)
        agkit.setClientRole(this.userRole)
        agkit.enableVideo()
        agkit.setVideoEncoderConfiguration(VideoEncoderConfiguration())
        agkit.registerVideoFrameObserver(VideoRecordObserver)
        println("Environment.getExternalStorageDirectory() = ")
        println(Environment.getExternalStorageDirectory())
        if (agoraSettings.rtmEnabled) {
            agoraRtmController.initAgoraRtm(context)
        }
    }

    fun saveFile () {
        println("write File " + "/test.txt")

        val storeDirectory = context.getExternalFilesDir(Environment.DIRECTORY_DCIM)
        val file = File(storeDirectory, "testing-again.png")
        val data:String = "test content"
        val fileOutputStream: FileOutputStream
        try {
            fileOutputStream = FileOutputStream(file)
            fileOutputStream.write(data.toByteArray())
            println("write File complete")
            fileOutputStream.close()

        } catch (e: FileNotFoundException){
            e.printStackTrace()
        }catch (e: NumberFormatException){
            e.printStackTrace()
        }catch (e: IOException){
            e.printStackTrace()
        }catch (e: Exception){
            e.printStackTrace()
        }
    }

    fun copyStreamToFile(inputStream: InputStream) {
        val storeDirectory = context.getExternalFilesDir(Environment.DIRECTORY_DCIM)
        val outputFile = File(storeDirectory, "testing-again.mp4")
        inputStream.use { input ->
            val outputStream = FileOutputStream(outputFile)
            outputStream.use { output ->
                val buffer = ByteArray(4 * 1024) // buffer size
                while (true) {
                    val byteCount = input.read(buffer)
                    if (byteCount < 0) break
                    output.write(buffer, 0, byteCount)
                }
                output.flush()
            }
        }
    }
    private var videoNV21Buffer: ByteBuffer? = null
    private lateinit var videoNV21: ByteArray
    private var isSnapshot = true

    private val VideoRecordObserver: IVideoFrameObserver = object : IVideoFrameObserver {
        override fun onCaptureVideoFrame(videoFrame: VideoFrame): Boolean {
            // Код отсюда: https://github.com/AgoraIO/API-Examples/blob/main/Android/APIExample/app/src/main/java/io/agora/api/example/examples/advanced/ProcessRawData.java
            // Log.i(TAG, "OnEncodedVideoImageReceived" + Thread.currentThread().name)

            val startTime = System.currentTimeMillis()
            val buffer = videoFrame.buffer

            // Obtain texture id from buffer.
            // if(buffer instanceof VideoFrame.TextureBuffer){
            //     int textureId = ((VideoFrame.TextureBuffer) buffer).getTextureId();
            // }


            // Obtain texture id from buffer.
            // if(buffer instanceof VideoFrame.TextureBuffer){
            //     int textureId = ((VideoFrame.TextureBuffer) buffer).getTextureId();
            // }
            val i420Buffer = buffer.toI420()
            val width = i420Buffer.width
            val height = i420Buffer.height

            // Test Result
            // device: HUAWEI DUB-AL00
            // consume time: 46ms, 54ms, 43ms, 47ms, 57ms, 42ms
            // byte[] i420 = YUVUtils.toWrappedI420(i420Buffer.getDataY(), i420Buffer.getDataU(), i420Buffer.getDataV(), width, height);
            // byte[] nv21 = YUVUtils.I420ToNV21(i420, width, height);

            // *Recommend method*.
            // Test Result
            // device: HUAWEI DUB-AL00
            // consume time: 11ms, 8ms, 10ms, 10ms, 9ms, 10ms

            // Test Result
            // device: HUAWEI DUB-AL00
            // consume time: 46ms, 54ms, 43ms, 47ms, 57ms, 42ms
            // byte[] i420 = YUVUtils.toWrappedI420(i420Buffer.getDataY(), i420Buffer.getDataU(), i420Buffer.getDataV(), width, height);
            // byte[] nv21 = YUVUtils.I420ToNV21(i420, width, height);

            // *Recommend method*.
            // Test Result
            // device: HUAWEI DUB-AL00
            // consume time: 11ms, 8ms, 10ms, 10ms, 9ms, 10ms
            val nv21MinSize = ((width * height * 3 + 1) / 2.0f).toInt()
            if (videoNV21Buffer == null || videoNV21Buffer!!.capacity() < nv21MinSize) {
                videoNV21Buffer = ByteBuffer.allocateDirect(nv21MinSize)
                videoNV21 = ByteArray(nv21MinSize)
            }
            YuvHelper.I420ToNV12(
                i420Buffer.dataY, i420Buffer.strideY,
                i420Buffer.dataV, i420Buffer.strideV,
                i420Buffer.dataU, i420Buffer.strideU,
                videoNV21Buffer, width, height
            )
            videoNV21Buffer?.position(0)
            videoNV21Buffer?.get(videoNV21)
            val nv21: ByteArray = videoNV21


            // Release the buffer!

            // Release the buffer!
            i420Buffer.release()


            if (isSnapshot) {
                isSnapshot = false
                val bitmap: Bitmap = YUVUtils.NV21ToBitmap(
                    context,
                    nv21,
                    width,
                    height
                )
                val matrix = Matrix()
                // matrix.setRotate(videoFrame.rotation)
                // 围绕原地进行旋转
                val newBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, false)
                // save to file
                saveBitmap2Gallery(newBitmap)
                bitmap.recycle()
            }

            videoFrame.replaceBuffer(
                NV21Buffer(nv21, width, height, null),
                videoFrame.rotation,
                videoFrame.timestampNs
            )
            return true
/*

            if (true) {

                // println(Environment.getExternalStorageDirectory())

                var buffer = videoFrame.buffer
                val w = buffer.width
                val h = buffer.height
                val cropX = (w - 320) / 2
                val cropY = (h - 240) / 2
                val cropWidth = 320
                val cropHeight = 240
                val scaleWidth = 320
                val scaleHeight = 240
                buffer =
                    buffer.cropAndScale(cropX, cropY, cropWidth, cropHeight, scaleWidth, scaleHeight)
                videoFrame.replaceBuffer(buffer, 270, videoFrame.timestampNs)
                println(buffer)
            }
            return true
*/
        }

        override fun onPreEncodeVideoFrame(videoFrame: VideoFrame): Boolean {
            return false
        }

        override fun onScreenCaptureVideoFrame(videoFrame: VideoFrame): Boolean {
            return false
        }

        override fun onPreEncodeScreenVideoFrame(videoFrame: VideoFrame): Boolean {
            return false
        }

        override fun onMediaPlayerVideoFrame(videoFrame: VideoFrame, i: Int): Boolean {
            return false
        }

        override fun onRenderVideoFrame(s: String, i: Int, videoFrame: VideoFrame): Boolean {
            return false
        }

        override fun getVideoFrameProcessMode(): Int {
            // The process mode of the video frame. 0 means read-only, and 1 means read-and-write.
            return 1
        }

        override fun getVideoFormatPreference(): Int {
            return 1
        }

        override fun getRotationApplied(): Boolean {
            return false
        }

        override fun getMirrorApplied(): Boolean {
            return false
        }

        override fun getObservedFramePosition(): Int {
            return 0
        }
    }

    open fun saveBitmap2Gallery(bm: Bitmap) {
        val currentTime = System.currentTimeMillis()

        // name the file
        val imageFileName = "IMG_AGORA_$currentTime.jpg"
        val imageFilePath: String
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) imageFilePath =
            Environment.DIRECTORY_PICTURES + File.separator + "Agora" + File.separator else imageFilePath =
            (Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES
            ).absolutePath
                    + File.separator) + "Agora" + File.separator

        // write to file
        val outputStream: OutputStream
        val resolver = context.contentResolver
        val newScreenshot = ContentValues()
        val insert: Uri?
        newScreenshot.put(MediaStore.Images.ImageColumns.DATE_ADDED, currentTime)
        newScreenshot.put(MediaStore.Images.ImageColumns.DISPLAY_NAME, imageFileName)
        newScreenshot.put(MediaStore.Images.ImageColumns.MIME_TYPE, "image/jpg")
        newScreenshot.put(MediaStore.Images.ImageColumns.WIDTH, bm.getWidth())
        newScreenshot.put(MediaStore.Images.ImageColumns.HEIGHT, bm.getHeight())
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                newScreenshot.put(MediaStore.Images.ImageColumns.RELATIVE_PATH, imageFilePath)
            } else {
                // make sure the path is existed
                val imageFileDir = File(imageFilePath)
                if (!imageFileDir.exists()) {
                    val mkdir = imageFileDir.mkdirs()
                    if (!mkdir) {
                        println("save failed, error: cannot create folder. Make sure app has the permission.")
                        return
                    }
                }
                newScreenshot.put(
                    MediaStore.Images.ImageColumns.DATA,
                    imageFilePath + imageFileName
                )
                newScreenshot.put(MediaStore.Images.ImageColumns.TITLE, imageFileName)
            }

            // insert a new image
            insert = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, newScreenshot)
            // write data
            outputStream = insert?.let { resolver.openOutputStream(it) }!!
            bm.compress(Bitmap.CompressFormat.PNG, 80, outputStream)
            outputStream!!.flush()
            outputStream!!.close()
            newScreenshot.clear()
            newScreenshot.put(MediaStore.Images.ImageColumns.SIZE, File(imageFilePath).length())
            resolver.update(insert, newScreenshot, null, null)
            println("save success, you can view it in gallery")
        } catch (e: java.lang.Exception) {
            println("save failed, error: " + e.message)
            e.printStackTrace()
        }
    }




    /**
     * Delegate for the AgoraVideoViewer, used for some important callback methods.
     */
    public var delegate: AgoraVideoViewerDelegate? = null

    internal var floatingVideoHolder: RecyclerView = RecyclerView(context)
    internal var backgroundVideoHolder: RecyclerView = RecyclerView(context)

    /**
     * Settings and customisations such as position of on-screen buttons, collection view of all channel members,
     * as well as agora video configuration.
     */
    public var agoraSettings: AgoraSettings = AgoraSettings()
        internal set

    /**
     * Style and organisation to be applied to all the videos in this AgoraVideoViewer.
     */
    public var style: Style
        set(value: Style) {
            val oldValue = field
            field = value
            if (oldValue != value) {
//                this.backgroundVideoHolder.visibility = if (value == Style.COLLECTION) INVISIBLE else VISIBLE
                this.reorganiseVideos()
            }
        }

    /**
     * RtcEngine being used by this AgoraVideoViewer
     */
    public lateinit var agkit: RtcEngine
        internal set

    /**
     * RTM client used by this [AgoraVideoViewer]
     */
    public lateinit var agRtmClient: RtmClient
        internal set
    lateinit var agRtmChannel: RtmChannel
        internal set

    fun isAgRtmChannelInitialized() = ::agRtmChannel.isInitialized

    fun isAgRtmClientInitialized() = ::agRtmClient.isInitialized

    // VideoControl

    internal fun setupAgoraVideo() {
        if (this.agkit.enableVideo() < 0) {
            Logger.getLogger("AgoraVideoUIKit").log(Level.WARNING, "Could not enable video")
            return
        }
        if (this.controlContainer == null) {
            this.addVideoButtons()
        }
        this.agkit.setVideoEncoderConfiguration(this.agoraSettings.videoConfiguration)
    }

    /**
     * Leave channel stops all preview elements
     * @return Same return as RtcEngine.leaveChannel, 0 means no problem, less than 0 means there was an issue leaving
     */
    fun leaveChannel(): Int {
        val channelName = this.connectionData.channel ?: return 0
        this.agkit.setupLocalVideo(null)
        if (this.userRole == Constants.CLIENT_ROLE_BROADCASTER) {
            this.agkit.stopPreview()
        }
        this.activeSpeaker = null
        (this.context as Activity).runOnUiThread {
            this.remoteUserIDs.forEach { this.removeUserVideo(it, false) }
            this.remoteUserIDs = mutableSetOf()
            this.userVideoLookup = mutableMapOf()
            this.reorganiseVideos()
            this.controlContainer?.visibility = INVISIBLE
        }

        val leaveChannelRtn = this.agkit.leaveChannel()
        if (leaveChannelRtn >= 0) {
            this.connectionData.channel = null
            this.delegate?.leftChannel(channelName)
        }
        return leaveChannelRtn
    }

    /**
     * Join the Agora channel with optional token request
     * @param channel: Channel name to join
     * @param fetchToken: Whether the token should be fetched before joining the channel. A token will only be fetched if a token URL is provided in AgoraSettings.
     * @param role: [AgoraClientRole](https://docs.agora.io/en/Video/API%20Reference/oc/Constants/AgoraClientRole.html) to join the channel as. Default: `.broadcaster`
     * @param uid: UID to be set when user joins the channel, default will be 0.
     */
    @JvmOverloads fun join(channel: String, fetchToken: Boolean, role: Int? = null, uid: Int? = null) {
        this.setupAgoraVideo()
        getRtcToken(channel, role, uid, fetchToken)

        if (agoraSettings.rtmEnabled) {
            getRtmToken(fetchToken)
        }
    }

    private fun getRtcToken(channel: String, role: Int? = null, uid: Int? = null, fetchToken: Boolean) {
        if (fetchToken) {
            this.agoraSettings.tokenURL?.let { tokenURL ->
                AgoraVideoViewer.Companion.fetchToken(
                    tokenURL, channel, uid ?: this.userID,
                    object : TokenCallback {
                        override fun onSuccess(token: String) {
                            this@AgoraVideoViewer.connectionData.appToken = token
                            this@AgoraVideoViewer.join(channel, token, role, uid)
                        }

                        override fun onError(error: TokenError) {
                            Logger.getLogger("AgoraVideoUIKit", "Could not get RTC token: ${error.name}")
                        }
                    }
                )
            }
            return
        }
        this.join(channel, this.connectionData.appToken, role, uid)
    }

    private fun getRtmToken(fetchToken: Boolean) {
        if (connectionData.rtmId.isNullOrEmpty()) {
            agoraRtmController.generateRtmId()
        }

        if (fetchToken) {
            this.agoraSettings.tokenURL?.let { tokenURL ->
                AgoraRtmController.Companion.fetchToken(
                    tokenURL,
                    rtmId = connectionData.rtmId as String,
                    completion = object : RtmTokenCallback {
                        override fun onSuccess(token: String) {
                            connectionData.rtmToken = token
                        }

                        override fun onError(error: RtmTokenError) {
                            Logger.getLogger("AgoraVideoUIKit", "Could not get RTM token: ${error.name}")
                        }
                    }
                )
            }
            return
        }
    }

    /**
     * Login to Agora RTM
     */
    fun triggerLoginToRtm() {
        if (agoraSettings.rtmEnabled && isAgRtmClientInitialized()) {
            agoraRtmController.loginToRtm()
        } else {
            Logger.getLogger("AgoraVideoUIKit")
                .log(Level.WARNING, "Username is null or RTM client has not been initialized")
        }
    }

    /**
     * Join the Agora channel with optional token request
     * @param channel: Channel name to join
     * @param token: token to be applied to the channel join. Leave null to use an existing token or no token.
     * @param role: [AgoraClientRole](https://docs.agora.io/en/Video/API%20Reference/oc/Constants/AgoraClientRole.html) to join the channel as.
     * @param uid: UID to be set when user joins the channel, default will be 0.
     */
    @JvmOverloads fun join(channel: String, token: String? = null, role: Int? = null, uid: Int? = null) {

        if (role == Constants.CLIENT_ROLE_BROADCASTER) {
            AgoraVideoViewer.requestPermission(this.context)
        }
        if (this.connectionData.channel != null) {
            if (this.connectionData.channel == channel) {
                // already in this channel
                return
            }
            val leaveChannelRtn = this.leaveChannel()
            if (leaveChannelRtn < 0) {
                // could not leave channel
                Logger.getLogger("AgoraVideoUIKit")
                    .log(Level.WARNING, "Could not leave channel: $leaveChannelRtn")
            } else {
                this.join(channel, token, role, uid)
            }
            return
        }
        role?.let {
            if (it != this.userRole) {
                this.userRole = it
            }
        }
        uid?.let {
            this.userID = it
        }

        this.setupAgoraVideo()
        this.agkit.joinChannel(token ?: this.agoraSettings.tokenURL, channel, null, this.userID)
    }
}
