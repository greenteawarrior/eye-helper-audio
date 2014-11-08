package com.eyehelper.positionalaudiocvtesting.views;

import android.app.Activity;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.util.Log;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import com.eyehelper.positionalaudiocvtesting.ObjectTracker;
import com.eyehelper.positionalaudiocvtesting.PositionalAudio;
import com.eyehelper.positionalaudiocvtesting.R;

import org.opencv.android.BaseLoaderCallback;
import org.opencv.android.CameraBridgeViewBase;
import org.opencv.android.LoaderCallbackInterface;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.CvType;
import org.opencv.core.Mat;

import butterknife.ButterKnife;
import butterknife.InjectView;


public class MyActivity extends Activity implements CameraBridgeViewBase.CvCameraViewListener2, SensorEventListener {
    private static final String TAG = "OCVSample::Activity";

    //The matrix of the image in rgba
    private Mat rgba;

    //Camera Parameters
    private double focal = 2.8;
    private int objWidth = 127;
    private int imgHeight = 512;
    private int sensorHeight = 4;

    //Position Variables
    //TODO: double check variable scope things: hypothesis is public in ObjectTracker.java
    //TODO: double check variable scope things: distance/angle/height are private variables in PositionalAudio.java

    //Camera View
    @InjectView(R.id.camera_view)
    CameraBridgeViewBase cameraPreview;

    @InjectView(R.id.text_view_height)
    TextView heightText;
    @InjectView(R.id.text_view_distance)
    TextView distanceText;
    @InjectView(R.id.text_view_azimuth)
    TextView azimuthText;
    @InjectView(R.id.text_view_pitch)
    TextView pitchText;
    @InjectView(R.id.text_view_roll)
    TextView rollText;
    @InjectView(R.id.send_image)
    Button test;

    //Sensor Data
    private SensorManager sensorManager;
    private Sensor accelerometer;
    private Sensor magnetometer;
    public float azimuth = 0.0f;
    public float pitch = 0.0f;
    public float roll = 0.0f;

    private float[] gravity;
    private float[] geomagnetic;

    private double imageRows;
    private double imageCols;

    private volatile boolean soundRunning;

    private ObjectTracker objectTracker;
    private PositionalAudio positionalAudio;

    boolean sendImage;

    //This is what we use to determine whether or not the app loaded successfully
    private BaseLoaderCallback loaderCallback = new BaseLoaderCallback(this) {
        @Override
        public void onManagerConnected(int status) {
            switch (status) {
                //The app loaded successfully
                case LoaderCallbackInterface.SUCCESS: {
                    Log.i(TAG, "OpenCV loaded successfully");
                    cameraPreview.enableView();
                    //cameraPreview.setOnTouchListener(MyActivity.this);
                }
                break;
                //Otherwise it didn't
                default: {
                    super.onManagerConnected(status);
                }
                break;
            }
        }
    };

    //When the activity is created
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);
        ButterKnife.inject(this);

        objectTracker = new ObjectTracker();
        positionalAudio = new PositionalAudio();

        final GestureDetector gestureDetector = new GestureDetector(this, new TapGestureListener());
        cameraPreview.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                gestureDetector.onTouchEvent(event);
                return true;
            }
        });

        // Sensors! (To get the head tilt information)
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);

        // why does this line of code happen twice?
        // Make this activity the listener for our camera view
        cameraPreview.setCvCameraViewListener(this);
        test.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                sendImage = true;
            }
        });
    }

    @Override
    public void onPause() {
        //When the app is paused, stop the camera and pause the music
        sensorManager.unregisterListener(this);
        if (cameraPreview != null) {
            cameraPreview.disableView();
        }
        soundRunning = false;
        super.onPause();
    }

    @Override
    public void onResume() {
        //When the app is resumed, restart the camera asynchronously
        OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION_2_4_3, this, loaderCallback);
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI);
        sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_UI);
        // TODO: re-enable this after we get the cv code working
//        startSoundThread();
        super.onResume();
    }

    private void startSoundThread() {
        soundRunning = true;
        Thread soundThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (soundRunning) {
                    try {
                        Thread.sleep(500);
                        positionalAudio.playSound(MyActivity.this);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }

                }
            }
        });
        soundThread.start();
    }

    //When a user swipes down to quit, finish the app
    @Override
    public boolean onKeyDown(int keycode, KeyEvent event) {
        if (keycode == KeyEvent.KEYCODE_BACK) {
            finish();
        }
        return false;
    }

    //Right after the camera starts
    @Override
    public void onCameraViewStarted(int width, int height) {
        //Declare the image matrix to be a matrix of the height and width of the image
        rgba = new Mat(height, width, CvType.CV_8UC4);
    }

    //when the camera view stops
    @Override
    public void onCameraViewStopped() {
        //When the camera view stops, release the camera
        rgba.release();
    }

    //Every time we get a new camera frame
    @Override
    public Mat onCameraFrame(CameraBridgeViewBase.CvCameraViewFrame inputFrame) {
        Mat greyImage = inputFrame.gray();
        imageCols = greyImage.cols();
        imageRows = greyImage.rows();

        if (!objectTracker.hasTrainingImage()) {
            objectTracker.saveCurrentImage(greyImage);
        }

        if (!greyImage.empty() && sendImage) {
            sendImage = false;
            objectTracker.matchObject(greyImage);
        }

//        if (objectTracker.coordinates != null) {
//            Core.rectangle(greyImage, objectTracker.coordinates.first, objectTracker.coordinates.first, new Scalar(0, 0, 255), 0, 8, 0);
//        }
        return greyImage;
    }

    //When the accuracy of a sensor changes
    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    //When the reading of a sensor changes
    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER)
            gravity = event.values;
        if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD)
            geomagnetic = event.values;
        if (gravity != null && geomagnetic != null) {
            float R[] = new float[9];
            float I[] = new float[9];
            boolean success = SensorManager.getRotationMatrix(R, I, gravity, geomagnetic);
            if (success) {
                float orientation[] = new float[3];
                SensorManager.getOrientation(R, orientation);
                azimuth = orientation[0]; // orientation contains: azimuth, pitch and roll
                pitch = orientation[1];
                roll = orientation[2];
            }
        }
    }

    class TapGestureListener extends GestureDetector.SimpleOnGestureListener {
        // TODO: Should TapGestureListener be a class within MyActivity or its own thing??
        // TODO: (futurImplement training image selection from the webapp
        @Override
        public boolean onSingleTapUp(MotionEvent e) {
            Log.v(TAG, String.format("x: %f, y: %f", e.getX(), e.getY()));

            double xMargin = (cameraPreview.getWidth() - imageCols) / 2;
            double yMargin = (cameraPreview.getHeight() - imageRows) / 2;

            double x = e.getX() - xMargin;
            double y = e.getY() - yMargin;

            if (x < 0 || y < 0 || imageCols < x || imageRows < y) {
                Log.v(TAG, "Tapped outside the image");
                return true;
            }

            return objectTracker.saveTrainingImage(x, y);
        }
    }
}
