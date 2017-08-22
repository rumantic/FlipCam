package com.flipcam.view;

import android.content.Context;
import android.graphics.Point;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.SensorManager;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.AttributeSet;
import android.util.Log;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.flipcam.R;
import com.flipcam.cameramanager.Camera1Manager;
import com.flipcam.constants.Constants;
import com.flipcam.util.GLUtil;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Created by Koushick on 15-08-2017.
 */

public class CameraView extends SurfaceView implements SurfaceHolder.Callback, SurfaceTexture.OnFrameAvailableListener {

    public static final String TAG = "CameraView";
    private static int VIDEO_WIDTH = 640;  // dimensions for VGA
    private static int VIDEO_HEIGHT = 480;
    CamcorderProfile camcorderProfile;
    SurfaceTexture surfaceTexture;
    private EGLDisplay mEGLDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext mEGLContext = EGL14.EGL_NO_CONTEXT;
    private EGLConfig mEGLConfig = null;
    // Android-specific extension.
    private static final int EGL_RECORDABLE_ANDROID = 0x3142;
    public static final int FLAG_RECORDABLE = 0x01;
    private int mProgramHandle;
    private int mTextureTarget;
    private int mTextureId;
    private int muMVPMatrixLoc;
    private static final int SIZEOF_FLOAT = 4;
    private int muTexMatrixLoc;
    private int maPositionLoc;
    private int maTextureCoordLoc;
    //Surface onto which camera frames are drawn
    EGLSurface eglSurface;
    //Surface to which camera frames are sent for encoding to mp4 format
    EGLSurface encoderSurface=null;
    SurfaceHolder camSurfHolder=null;
    private final float[] mTmpMatrix = new float[16];
    public static final float[] IDENTITY_MATRIX;
    public static final float[] RECORD_IDENTITY_MATRIX;
    static {
        IDENTITY_MATRIX = new float[16];
        Matrix.setIdentityM(IDENTITY_MATRIX, 0);
        RECORD_IDENTITY_MATRIX = new float[16];
        Matrix.setIdentityM(RECORD_IDENTITY_MATRIX, 0);}

    CameraRenderer.CameraHandler cameraHandler;
    TimeElapsed.TimeElapsedHandler timeElapsedHandler;
    TimeElapsed timeElapsedUpdate;
    MainHandler mainHandler;
    Object renderObj = new Object();
    volatile boolean isReady=false;
    boolean VERBOSE=false;
    //Keep in portrait by default.
    boolean portrait=true;
    float rotationAngle = 0.0f;
    boolean backCamera = true;
    volatile boolean isRecord = false;
    MediaRecorder mediaRecorder = null;
    int frameCount=0;
    volatile int cameraFrameCnt=0;
    volatile int frameCnt=0;
    Camera1Manager camera1;
    boolean isFocusModeSupported=false;
    int orientation = -1;
    OrientationEventListener orientationEventListener;
    volatile int hour=0;
    volatile int minute=0;
    volatile int second=0;
    String mNextVideoAbsolutePath=null;
    TextView timeElapsed;
    TextView memoryConsumed;
    //Default display sizes in case windowManager is not able to return screen size.
    int measuredWidth = 800;
    int measuredHeight = 600;
    int currentZoom = 0;
    String focusMode = Camera.Parameters.FOCUS_MODE_AUTO;
    String flashMode = Camera.Parameters.FLASH_MODE_OFF;
    ImageButton flashBtn;

    public CameraView(Context context, AttributeSet attrs) {
        super(context, attrs);
        Log.d(TAG,"start cameraview");
        getHolder().addCallback(this);
        camera1 = Camera1Manager.getInstance();
    }

    class MainHandler extends Handler {
        WeakReference<CameraView> cameraView;
        CameraView camView;

        public MainHandler(CameraView cameraView1) {
            cameraView = new WeakReference<>(cameraView1);
        }

        @Override
        public void handleMessage(Message msg) {
            camView = cameraView.get();
            switch(msg.what)
            {
                case Constants.SHOW_ELAPSED_TIME:
                    //displayComplete();
                    Log.d(TAG,"show time now");
                    camView.showTimeElapsed();
                    break;
            }
        }
    }

    void waitUntilReady()
    {
        Log.d(TAG,"Waiting....");
        synchronized (renderObj)
        {
            while(!isReady){
                try {
                    renderObj.wait();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
        Log.d(TAG,"Come out of WAIT");
    }

    @Override
    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
        if(VERBOSE)Log.d(TAG,"FRAME Available now");
        if(VERBOSE)Log.d(TAG,"is Record = "+isRecord);
        if(isRecord){
            if(VERBOSE)Log.d(TAG,"Frame avail cnt = "+(++cameraFrameCnt));
        }
        cameraHandler.sendEmptyMessage(Constants.FRAME_AVAILABLE);
    }

    private void prepareEGLDisplayandContext()
    {
        mEGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (mEGLDisplay == EGL14.EGL_NO_DISPLAY) {
            throw new RuntimeException("unable to get EGL14 display");
        }
        int[] version = new int[2];
        if (!EGL14.eglInitialize(mEGLDisplay, version, 0, version, 1)) {
            mEGLDisplay = null;
            throw new RuntimeException("unable to initialize EGL14");
        }
        EGLConfig config = getConfig(FLAG_RECORDABLE, 2);
        if (config == null) {
            throw new RuntimeException("Unable to find a suitable EGLConfig");
        }
        int[] attrib2_list = {
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
        };
        EGLContext context = EGL14.eglCreateContext(mEGLDisplay, config, EGL14.EGL_NO_CONTEXT,
                attrib2_list, 0);
        checkEglError("eglCreateContext");
        mEGLConfig = config;
        mEGLContext = context;

        // Confirm with query.
        int[] values = new int[1];
        EGL14.eglQueryContext(mEGLDisplay, mEGLContext, EGL14.EGL_CONTEXT_CLIENT_VERSION,
                values, 0);
        Log.d(TAG, "EGLContext created, client version " + values[0]);
    }

    private void checkEglError(String msg) {
        int error;
        if ((error = EGL14.eglGetError()) != EGL14.EGL_SUCCESS) {
            throw new RuntimeException(msg + ": EGL error: 0x" + Integer.toHexString(error));
        }
    }

    private EGLConfig getConfig(int flags, int version) {
        int renderableType = EGL14.EGL_OPENGL_ES2_BIT;

        int[] attribList = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, renderableType,
                EGL14.EGL_NONE, 0,      // placeholder for recordable [@-3]
                EGL14.EGL_NONE
        };
        if ((flags & FLAG_RECORDABLE) != 0) {
            attribList[attribList.length - 3] = EGL_RECORDABLE_ANDROID;
            attribList[attribList.length - 2] = 1;
        }
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        if (!EGL14.eglChooseConfig(mEGLDisplay, attribList, 0, configs, 0, configs.length,
                numConfigs, 0)) {
            Log.w(TAG, "unable to find RGB8888 / " + version + " EGLConfig");
            return null;
        }
        return configs[0];
    }

    public String getMediaPath(){
        return mNextVideoAbsolutePath;
    }

    public boolean zoomInAndOut(int progress)
    {
        return camera1.zoomInOrOut(progress);
    }

    public boolean isZoomSupported()
    {
        return camera1.isZoomSupported();
    }

    public boolean isSmoothZoomSupported()
    {
        return camera1.isSmoothZoomSupported();
    }

    public void smoothZoomInOrOut(int zoom)
    {
        camera1.smoothZoomInOrOut(zoom);
    }
    SeekBar seekBar;
    public void setSeekBar(SeekBar seekBar)
    {
        this.seekBar = seekBar;
    }

    public void setWindowManager(WindowManager winMgr)
    {
        Point size=new Point();
        winMgr.getDefaultDisplay().getSize(size);
        measuredHeight = ((size != null) ? size.y : measuredHeight);
        measuredWidth = ((size != null) ? size.x : measuredWidth);
    }

    public void setTimeElapsedText(TextView timeElapsedText)
    {
        Log.d(TAG,"Time elapsed textview set");
        timeElapsed = timeElapsedText;
    }

    public void showTimeElapsed()
    {
        Log.d(TAG,"displaying time = "+second);
        String showSec = "0";
        String showMin = "0";
        String showHr = "0";
        if(second < 10){
            showSec += second;
        }
        else{
            showSec = second+"";
        }

        if(minute < 10){
            showMin += minute;
        }
        else{
            showMin = minute+"";
        }

        if(hour < 10){
            showHr += hour;
        }
        else{
            showHr = hour+"";
        }
        timeElapsed.setText(showHr + " : " + showMin + " : " + showSec);
    }

    public void setFlashButton(ImageButton flashButton)
    {
        flashBtn = flashButton;
    }

    public void switchCamera()
    {
        if(backCamera)
        {
            backCamera = false;
        }
        else
        {
            backCamera = true;
        }
        currentZoom = camera1.getCurrentZoom();
        focusMode = camera1.getFocusMode();
        flashMode = camera1.getFlashMode();
        camera1.stopPreview();
        camera1.releaseCamera();
        isFocusModeSupported = false;
        openCameraAndStartPreview();
    }
    public void flashOnOff(boolean flashOn)
    {
        if(flashOn)
        {
            camera1.setTorchLight();
        }
        else
        {
            camera1.setFlashOnOff(false);
        }
    }

    public boolean isFlashModeSupported(String flashMode)
    {
        return camera1.isFlashModeSupported(flashMode);
    }

    public void openCameraAndStartPreview()
    {
        camera1.openCamera(backCamera);
        if(!camera1.isCameraReady()){
            Toast.makeText(getContext(),"Front facing camera not available in this device.",Toast.LENGTH_SHORT).show();
            return;
        }
        camera1.setResolution(measuredWidth, measuredHeight);
        camera1.setFPS();
        setLayoutAspectRatio();
        camera1.startPreview(surfaceTexture);
        camera1.zoomInOrOut(currentZoom);
        this.seekBar.setMax(camera1.getMaxZoom());
        Log.d(TAG,"Setting max zoom = "+camera1.getMaxZoom());
        //Set the focus mode of previous camera
        if(!isRecord) {
            if (camera1.isFocusModeSupported(Camera.Parameters.FOCUS_MODE_AUTO)) {
                isFocusModeSupported = true;
            } else {
                isFocusModeSupported = false;
            }
        }
        else{
            if (camera1.isFocusModeSupported(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
                isFocusModeSupported = true;
            } else {
                isFocusModeSupported = false;
            }
        }
        //Set the flash mode of previous camera
        if(camera1.isFlashModeSupported(flashMode)){
            if(flashMode.equalsIgnoreCase(Camera.Parameters.FLASH_MODE_OFF)) {
                flashOnOff(false);
                flashBtn.setImageDrawable(getResources().getDrawable(R.drawable.flash_on));
            }
            else if(flashMode.equalsIgnoreCase(Camera.Parameters.FLASH_MODE_TORCH)){
                flashOnOff(true);
                flashBtn.setImageDrawable(getResources().getDrawable(R.drawable.flash_off));
            }
        }
        else{
            if(flashMode != null && !flashMode.equalsIgnoreCase(Camera.Parameters.FLASH_MODE_OFF)) {
                Toast.makeText(getContext(), "Flash Mode " + flashMode + " not supported by this camera.", Toast.LENGTH_SHORT).show();
            }
            flashBtn.setImageDrawable(getResources().getDrawable(R.drawable.flash_on));
        }
    }

    public void record()
    {
        if(!isRecord) {
            /*determineOrientation();
            Log.d(TAG,"Rot angle == "+rotationAngle+", portrait = "+portrait);
            Matrix.rotateM(RECORD_IDENTITY_MATRIX, 0, rotationAngle , 0, 0, 1);
            setLayoutAspectRatio();*/
            isRecord=true;
            timeElapsedUpdate = new TimeElapsed();
            timeElapsedUpdate.start();
            isReady = false;
            waitUntilReady();
            timeElapsedHandler.sendEmptyMessage(Constants.START_TIMER);
            //cameraHandler.sendEmptyMessage(Constants.RECORD_START);
            Toast.makeText(getContext(),"Record started",Toast.LENGTH_SHORT).show();
        }
        else{
            isRecord=false;
            //timeElapsedHandler.sendEmptyMessage(Constants.STOP_TIMER);
            /*cameraHandler.sendEmptyMessage(Constants.RECORD_STOP);
            //Reset the RECORD Matrix to be portrait.
            System.arraycopy(IDENTITY_MATRIX,0,RECORD_IDENTITY_MATRIX,0,IDENTITY_MATRIX.length);
            //Reset Rotation angle
            rotationAngle = 0f;*/
            Toast.makeText(getContext(),"Record stopped",Toast.LENGTH_SHORT).show();
        }
    }

    public void determineOrientation() {

        if(orientation != -1) {
            if (((orientation >= 315 && orientation <= 359) || (orientation >= 0 && orientation <= 45)) || (orientation >= 135 && orientation <= 195)) {
                if (orientation >= 135 && orientation <= 195) {
                    rotationAngle = 180f;
                } else {
                    rotationAngle = 0f;
                }
                portrait = true;
            } else {
                if (orientation >= 46 && orientation <= 134) {
                    rotationAngle = 270f;
                } else {
                    rotationAngle = 90f;
                }
                portrait = false;
            }
        }
        else{
            //This device is on a flat surface or parallel to the ground. Default to portrait.
            portrait = true;
            rotationAngle = 0f;
        }
    }

    public void setLayoutAspectRatio()
    {
        // Set the preview aspect ratio.
        requestLayout();
        ViewGroup.LayoutParams layoutParams = getLayoutParams();
        VIDEO_WIDTH = camera1.getPreviewSizes()[0];
        VIDEO_HEIGHT = camera1.getPreviewSizes()[1];
        int temp = VIDEO_HEIGHT;
        VIDEO_HEIGHT = VIDEO_WIDTH;
        VIDEO_WIDTH = temp;
        layoutParams.height = VIDEO_HEIGHT;
        layoutParams.width = VIDEO_WIDTH;
        Log.d(TAG,"LP Height = "+layoutParams.height);
        Log.d(TAG,"LP Width = "+layoutParams.width);
        if(!portrait) {
            temp = VIDEO_HEIGHT;
            VIDEO_HEIGHT = VIDEO_WIDTH;
            VIDEO_WIDTH = temp;
        }
        int degree;
        if(backCamera) {
            degree = 180;
        }
        else{
            degree = 0;
        }
        Log.d(TAG,"Orientation == "+camera1.getCameraInfo().orientation);
        int result = (camera1.getCameraInfo().orientation + degree) % 360;
        result = (360 - result) % 360;
        Log.d(TAG,"Result == "+result);
        camera1.setDisplayOrientation(result);
    }

    private void releaseEGLSurface(){
        EGL14.eglDestroySurface(mEGLDisplay,eglSurface);
    }

    private void releaseProgram(){
        GLES20.glDeleteProgram(mProgramHandle);
    }

    private void releaseEGLContext()
    {
        if (mEGLDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(mEGLDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT);
            EGL14.eglDestroyContext(mEGLDisplay, mEGLContext);
            EGL14.eglReleaseThread();
            EGL14.eglTerminate(mEGLDisplay);
        }
        mEGLDisplay = EGL14.EGL_NO_DISPLAY;
        mEGLContext = EGL14.EGL_NO_CONTEXT;
        mEGLConfig = null;
    }

    @Override
    public void surfaceCreated(SurfaceHolder surfaceHolder) {
        Log.d(TAG, "surfCreated holder = " + surfaceHolder);
        camSurfHolder = surfaceHolder;
        mainHandler = new MainHandler(this);
        prepareEGLDisplayandContext();
        CameraRenderer cameraRenderer = new CameraRenderer();
        cameraRenderer.start();
        waitUntilReady();
        orientationEventListener = new OrientationEventListener(getContext(), SensorManager.SENSOR_DELAY_UI){
            @Override
            public void onOrientationChanged(int i) {
                if(orientationEventListener.canDetectOrientation()) {
                    orientation = i;
                }
            }
        };
        orientationEventListener.enable();
    }

    @Override
    public void surfaceChanged(SurfaceHolder surfaceHolder, int i, int width, int height) {
        Log.d(TAG,"surfaceChanged = "+surfaceHolder);
        Log.d(TAG,"Width = "+width+", height = "+height);
        if(!camera1.isCameraReady()) {
            measuredWidth = width;
            measuredHeight = height;
            frameCount=0;
            openCameraAndStartPreview();
        }
        if(surfaceTexture!=null) {
            surfaceTexture.setOnFrameAvailableListener(this);
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder surfaceHolder) {
        Log.d(TAG,"surfaceDestroyed = "+surfaceHolder);
        Log.d(TAG,"cameraHandler = "+cameraHandler);
        if(cameraHandler!=null) {
            CameraRenderer cameraRenderer = cameraHandler.getCameraRendererInstance();
            cameraHandler.sendEmptyMessage(Constants.SHUTDOWN);
            try {
                if(cameraRenderer!=null){
                    cameraRenderer.join();
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        if(surfaceTexture!=null){
            surfaceTexture.release();
            surfaceTexture=null;
        }
        if(camera1.isCameraReady()) {
            camera1.stopPreview();
            camera1.releaseCamera();
        }
        orientationEventListener.disable();
        frameCount=0;
        releaseEGLSurface();
        releaseProgram();
        releaseEGLContext();
    }

    class CameraRenderer extends Thread
    {
        int recordStop = -1;
        boolean isRecording = false;

        public CameraRenderer()
        {
            //Empty constructor
        }

        @Override
        public void run()
        {
            Looper.prepare();
            cameraHandler = new CameraHandler(this);
            createSurfaceTexture();
            synchronized (renderObj){
                isReady=true;
                renderObj.notify();
            }
            if(VERBOSE)Log.d(TAG,"Main thread notified");
            Looper.loop();
            Log.d(TAG,"Camera Renderer STOPPED");
        }

        private void makeCurrent(EGLSurface surface)
        {
            EGL14.eglMakeCurrent(mEGLDisplay, surface, surface, mEGLContext);
        }
        /**
         * Creates a texture object suitable for use with this program.
         * <p>
         * On exit, the texture will be bound.
         */
        public int createGLTextureObject() {
            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);
            GLUtil.checkGlError("glGenTextures");

            int texId = textures[0];
            GLES20.glBindTexture(mTextureTarget, texId);
            GLUtil.checkGlError("glBindTexture " + texId);

            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER,
                    GLES20.GL_NEAREST);
            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER,
                    GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S,
                    GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T,
                    GLES20.GL_CLAMP_TO_EDGE);
            GLUtil.checkGlError("glTexParameter");

            return texId;
        }

        private EGLSurface prepareWindowSurface(Surface surface)
        {
            // Create a window surface, and attach it to the Surface we received.
            int[] surfaceAttribs = {
                    EGL14.EGL_NONE
            };
            EGLSurface surface1;
            surface1 = EGL14.eglCreateWindowSurface(mEGLDisplay, mEGLConfig,surface ,
                    surfaceAttribs, 0);
            checkEglError("eglCreateWindowSurface");
            if (surface1 == null) {
                throw new RuntimeException("surface was null");
            }
            return surface1;
        }

        void createSurfaceTexture()
        {
            eglSurface = prepareWindowSurface(camSurfHolder.getSurface());
            makeCurrent(eglSurface);
            mProgramHandle = GLUtil.createProgram(GLUtil.VERTEX_SHADER, GLUtil.FRAGMENT_SHADER_EXT);
            maPositionLoc = GLES20.glGetAttribLocation(mProgramHandle, "aPosition");
            GLUtil.checkLocation(maPositionLoc, "aPosition");
            maTextureCoordLoc = GLES20.glGetAttribLocation(mProgramHandle, "aTextureCoord");
            GLUtil.checkLocation(maTextureCoordLoc, "aTextureCoord");
            muMVPMatrixLoc = GLES20.glGetUniformLocation(mProgramHandle, "uMVPMatrix");
            GLUtil.checkLocation(muMVPMatrixLoc, "uMVPMatrix");
            muTexMatrixLoc = GLES20.glGetUniformLocation(mProgramHandle, "uTexMatrix");
            GLUtil.checkLocation(muTexMatrixLoc, "uTexMatrix");
            mTextureTarget = GLES11Ext.GL_TEXTURE_EXTERNAL_OES;
            mTextureId = createGLTextureObject();
            surfaceTexture = new SurfaceTexture(mTextureId);
        }
        /**
         * Issues the draw call.  Does the full setup on every call.
         *
         * @param mvpMatrix The 4x4 projection matrix.
         * @param vertexBuffer Buffer with vertex position data.
         * @param firstVertex Index of first vertex to use in vertexBuffer.
         * @param vertexCount Number of vertices in vertexBuffer.
         * @param coordsPerVertex The number of coordinates per vertex (e.g. x,y is 2).
         * @param vertexStride Width, in bytes, of the position data for each vertex (often
         *        vertexCount * sizeof(float)).
         * @param texMatrix A 4x4 transformation matrix for texture coords.  (Primarily intended
         *        for use with SurfaceTexture.)
         * @param texBuffer Buffer with vertex texture data.
         * @param texStride Width, in bytes, of the texture data for each vertex.
         */
        private void draw(float[] mvpMatrix, FloatBuffer vertexBuffer, int firstVertex,
                          int vertexCount, int coordsPerVertex, int vertexStride,
                          float[] texMatrix, FloatBuffer texBuffer, int textureId, int texStride) {
            GLUtil.checkGlError("draw start");

            // Select the program.
            GLES20.glUseProgram(mProgramHandle);
            GLUtil.checkGlError("glUseProgram");

            // Set the texture.
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(mTextureTarget, textureId);

            // Copy the model / view / projection matrix over.
            GLES20.glUniformMatrix4fv(muMVPMatrixLoc, 1, false, mvpMatrix, 0);
            GLUtil.checkGlError("glUniformMatrix4fv");

            // Copy the texture transformation matrix over.
            GLES20.glUniformMatrix4fv(muTexMatrixLoc, 1, false, texMatrix, 0);
            GLUtil.checkGlError("glUniformMatrix4fv");

            // Enable the "aPosition" vertex attribute.
            GLES20.glEnableVertexAttribArray(maPositionLoc);
            GLUtil.checkGlError("glEnableVertexAttribArray");

            // Connect vertexBuffer to "aPosition".
            GLES20.glVertexAttribPointer(maPositionLoc, coordsPerVertex,
                    GLES20.GL_FLOAT, false, vertexStride, vertexBuffer);
            GLUtil.checkGlError("glVertexAttribPointer");

            // Enable the "aTextureCoord" vertex attribute.
            GLES20.glEnableVertexAttribArray(maTextureCoordLoc);
            GLUtil.checkGlError("glEnableVertexAttribArray");

            // Connect texBuffer to "aTextureCoord".
            GLES20.glVertexAttribPointer(maTextureCoordLoc, 2,
                    GLES20.GL_FLOAT, false, texStride, texBuffer);
            GLUtil.checkGlError("glVertexAttribPointer");

            // Draw the rect.
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, firstVertex, vertexCount);
            GLUtil.checkGlError("glDrawArrays");

            // Done -- disable vertex array, texture, and program.
            GLES20.glDisableVertexAttribArray(maPositionLoc);
            GLES20.glDisableVertexAttribArray(maTextureCoordLoc);
            GLES20.glBindTexture(mTextureTarget, 0);
            GLES20.glUseProgram(0);
        }

        /**
         * Allocates a direct float buffer, and populates it with the float array data.
         */
        private FloatBuffer createFloatBuffer(float[] coords) {
            // Allocate a direct ByteBuffer, using 4 bytes per float, and copy coords into it.
            ByteBuffer bb = ByteBuffer.allocateDirect(coords.length * SIZEOF_FLOAT);
            bb.order(ByteOrder.nativeOrder());
            FloatBuffer fb = bb.asFloatBuffer();
            fb.put(coords);
            fb.position(0);
            return fb;
        }

        void setupMediaRecorder()
        {
            camcorderProfile = CamcorderProfile.get(camera1.getCameraId(),CamcorderProfile.QUALITY_HIGH);
            mediaRecorder = new MediaRecorder();
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            if (mNextVideoAbsolutePath == null || mNextVideoAbsolutePath.isEmpty()) {
                mNextVideoAbsolutePath = getVideoFilePath();
            }
            mediaRecorder.setOutputFile(mNextVideoAbsolutePath);
            mediaRecorder.setVideoEncodingBitRate(camcorderProfile.videoBitRate);
            mediaRecorder.setVideoFrameRate(camcorderProfile.videoFrameRate);
            mediaRecorder.setVideoSize(VIDEO_WIDTH, VIDEO_HEIGHT);
            mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            try {
                mediaRecorder.prepare();
            } catch (IOException e) {
                e.printStackTrace();
            }
            encoderSurface = prepareWindowSurface(mediaRecorder.getSurface());
        }

        private String getVideoFilePath() {
            File dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM+"/FlipCam/FC_Videos/");
            if(!dcim.exists())
            {
                dcim.mkdirs();
            }
            String path = dcim.getPath()+"/FC_VID_"+System.currentTimeMillis()+".mp4";
            Log.d(TAG,"Saving media file at = "+path);
            return path;
        }

        void drawFrame()
        {
            if(mEGLConfig!=null && camera1.isCameraReady()) {
                makeCurrent(eglSurface);
                if(VERBOSE) Log.d(TAG,"made current");
                //Get next frame from camera
                surfaceTexture.updateTexImage();
                surfaceTexture.getTransformMatrix(mTmpMatrix);

                //Fill the surfaceview with Camera frame
                int viewWidth = getWidth();
                int viewHeight = getHeight();
                if (frameCount == 0) {
                    if(VERBOSE)Log.d(TAG, "FRAME Count = "+frameCount);
                    Log.d(TAG,"SV Width == "+viewWidth+", SV Height == "+viewHeight);
                }
                GLES20.glViewport(0, 0, viewWidth, viewHeight);
                draw(IDENTITY_MATRIX, createFloatBuffer(GLUtil.FULL_RECTANGLE_COORDS), 0, (GLUtil.FULL_RECTANGLE_COORDS.length / 2), 2, 2 * SIZEOF_FLOAT, mTmpMatrix,
                        createFloatBuffer(GLUtil.FULL_RECTANGLE_TEX_COORDS), mTextureId, 2 * SIZEOF_FLOAT);

                if(VERBOSE)Log.d(TAG, "Draw on screen...."+isRecording);
                //Calls eglSwapBuffers.  Use this to "publish" the current frame.
                EGL14.eglSwapBuffers(mEGLDisplay, eglSurface);
                //Try to refocus if focus is lost
                if(isFocusModeSupported && !camera1.isAutoFocus() && recordStop != 1) {
                    camera1.setAutoFocus(Camera.Parameters.FOCUS_MODE_AUTO);
                }

                if(isRecording) {
                    makeCurrent(encoderSurface);
                    if (VERBOSE) Log.d(TAG, "Made encoder surface current");
                    GLES20.glViewport(0, 0, VIDEO_WIDTH, VIDEO_HEIGHT);
                    draw(RECORD_IDENTITY_MATRIX, createFloatBuffer(GLUtil.FULL_RECTANGLE_COORDS), 0, (GLUtil.FULL_RECTANGLE_COORDS.length / 2), 2, 2 * SIZEOF_FLOAT, mTmpMatrix,
                            createFloatBuffer(GLUtil.FULL_RECTANGLE_TEX_COORDS), mTextureId, 2 * SIZEOF_FLOAT);
                    if (VERBOSE) Log.d(TAG, "Populated to encoder");
                    if (recordStop == -1) {
                        camera1.setAutoFocus(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
                        mediaRecorder.start();
                        recordStop = 1;
                    }
                    EGLExt.eglPresentationTimeANDROID(mEGLDisplay, encoderSurface, surfaceTexture.getTimestamp());
                    EGL14.eglSwapBuffers(mEGLDisplay, encoderSurface);
                }
            }
            frameCount++;
        }

        void shutdown()
        {
            Looper.myLooper().quit();
        }

        class CameraHandler extends Handler
        {
            WeakReference<CameraRenderer> cameraRender;
            CameraRenderer cameraRenderer;

            public CameraHandler(CameraRenderer cameraRenderer){
                cameraRender = new WeakReference<>(cameraRenderer);
            }

            private CameraRenderer getCameraRendererInstance()
            {
                return cameraRenderer;
            }

            @Override
            public void handleMessage(Message msg) {
                cameraRenderer = cameraRender.get();
                switch(msg.what)
                {
                    case Constants.SHUTDOWN:
                        Log.d(TAG,"Shutdown msg received");
                        cameraRenderer.shutdown();
                        break;
                    case Constants.FRAME_AVAILABLE:
                        if(VERBOSE)Log.d(TAG,"send to FRAME_AVAILABLE");
                        cameraRenderer.drawFrame();
                        if(VERBOSE)Log.d(TAG,"Record = "+isRecord);
                        if(isRecord){
                            if(VERBOSE)Log.d(TAG,"render frame = "+(++frameCnt));
                        }
                        break;
                    case Constants.RECORD_START:
                        isRecording = true;
                        cameraRenderer.setupMediaRecorder();
                        break;
                    case Constants.RECORD_STOP:
                        isRecording = false;
                        recordStop = -1;
                        mediaRecorder.stop();
                        mediaRecorder.release();
                        mediaRecorder = null;
                        Log.d(TAG,"stop isRecording == "+isRecording);
                        if(VERBOSE)Log.d(TAG, "Exit recording...");
                        Log.d(TAG,"Orig frame = "+frameCount+" , Rendered frame "+frameCnt);
                        break;
                    case Constants.GET_CAMERA_RENDERER_INSTANCE:
                        getCameraRendererInstance();
                        break;
                }
            }
        }
    }

    class TimeElapsed extends Thread
    {
        public TimeElapsed()
        {
            //Empty constructor
        }

        @Override
        public void run()
        {
            Log.d(TAG,"Time thread start");
            Looper.prepare();
            timeElapsedHandler = new TimeElapsedHandler(this);
            synchronized (renderObj){
                isReady = true;
                renderObj.notify();
            }
            Looper.loop();
            Log.d(TAG,"Timer thread STOPPED");
        }

        public void stopTimer()
        {
            //timerStart = false;
            Log.d(TAG,"stop timer");
            Looper.myLooper().quit();
        }

        public void showUpdatedTime()
        {
            hour = 0; minute = 0; second = -1;
            while(isRecord) {
                if(second < 59) {
                    second++;
                    Log.d(TAG,"second = "+second);
                }
                else if(minute < 59){
                    second = 0;
                    minute++;
                    Log.d(TAG,"minute = "+minute);
                }
                else{
                    second = 0;
                    minute = 0;
                    hour++;
                    Log.d(TAG,"hour = "+hour);
                }
                mainHandler.sendEmptyMessage(Constants.SHOW_ELAPSED_TIME);
                try {
                    //To update time every 1 second
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            Log.d(TAG,"timer done");
            stopTimer();
        }

        class TimeElapsedHandler extends Handler
        {
            WeakReference<TimeElapsed> timeElapsedRef;
            TimeElapsed timeElapsed;

            private TimeElapsed getTimeElapsedReference()
            {
                return timeElapsed;
            }

            public TimeElapsedHandler(TimeElapsed timeElapsed1)
            {
                timeElapsedRef = new WeakReference<>(timeElapsed1);
            }

            @Override
            public void handleMessage(Message msg) {
                timeElapsed = timeElapsedRef.get();
                switch (msg.what)
                {
                    case Constants.GET_TIME_ELAPSED_REFERENCE:
                        getTimeElapsedReference();
                        break;
                    case Constants.START_TIMER:
                        timeElapsed.showUpdatedTime();
                        break;
                }
            }
        }
    }
}