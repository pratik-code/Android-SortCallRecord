package atul.r.raut;

import java.io.File;
import java.io.IOException;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.media.MediaPlayer.OnCompletionListener;
import android.media.MediaPlayer.OnErrorListener;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;

public class Recorder implements OnCompletionListener, OnErrorListener {
    static final String TAG = "Recorder";
    static final String SAMPLE_PREFIX = "Atul-recording";
    static final String SAMPLE_PATH_KEY = "sample_path";
    static final String SAMPLE_LENGTH_KEY = "sample_length";

    public static final int IDLE_STATE = 0;
    public static final int RECORDING_STATE = 1;
    public static final int PLAYING_STATE = 2;
    
    int mState = IDLE_STATE;

    public static final int NO_ERROR = 0;
    public static final int SDCARD_ACCESS_ERROR = 1;
    public static final int INTERNAL_ERROR = 2;
    public static final int IN_CALL_RECORD_ERROR = 3;
    public static final int UNSUPPORTED_FORMAT = 4;
    
    public interface OnStateChangedListener {
        public void onStateChanged(int state);
        public void onError(int error);
    }
    OnStateChangedListener mOnStateChangedListener = null;
    
    long mSampleStart = 0;       // time at which latest record or play operation started
    int mSampleLength = 0;      // length of current sample
    File mSampleFile = null;
    
    MediaRecorder mRecorder = null;
    MediaPlayer mPlayer = null;
    
    public Recorder() {
    }
    
    public void saveState(Bundle recorderState) {
        recorderState.putString(SAMPLE_PATH_KEY, mSampleFile.getAbsolutePath());
        recorderState.putInt(SAMPLE_LENGTH_KEY, mSampleLength);
    }
    
    public int getMaxAmplitude() {
        if (mState != RECORDING_STATE)
            return 0;
        return mRecorder.getMaxAmplitude();
    }
    
    public void restoreState(Bundle recorderState) {
        String samplePath = recorderState.getString(SAMPLE_PATH_KEY);
        if (samplePath == null)
            return;
        int sampleLength = recorderState.getInt(SAMPLE_LENGTH_KEY, -1);
        if (sampleLength == -1)
            return;

        File file = new File(samplePath);
        if (!file.exists())
            return;
        if (mSampleFile != null
                && mSampleFile.getAbsolutePath().compareTo(file.getAbsolutePath()) == 0)
            return;
        
        delete();
        mSampleFile = file;
        mSampleLength = sampleLength;

        signalStateChanged(IDLE_STATE);
    }
    
    public void setOnStateChangedListener(OnStateChangedListener listener) {
        mOnStateChangedListener = listener;
    }
    
    public int state() {
        return mState;
    }
    
    public int progress() {
        if (mState == RECORDING_STATE || mState == PLAYING_STATE)
            return (int) ((System.currentTimeMillis() - mSampleStart)/1000);
        return 0;
    }
    
    public int sampleLength() {
        return mSampleLength;
    }

    public File sampleFile() {
        return mSampleFile;
    }
    
    /**
     * Resets the recorder state. If a sample was recorded, the file is deleted.
     */
    public void delete() {
        stop();
        
        if (mSampleFile != null)
            mSampleFile.delete();

        mSampleFile = null;
        mSampleLength = 0;
        
        signalStateChanged(IDLE_STATE);
    }
    
    /**
     * Resets the recorder state. If a sample was recorded, the file is left on disk and will 
     * be reused for a new recording.
     */
    public void clear() {
        stop();
        
        mSampleLength = 0;
        
        signalStateChanged(IDLE_STATE);
    }
    
    public void startRecording(int outputfileformat, String extension, Context context, int audiosourcetype, int codectype) {
        stop();
        Log.e (TAG, "ATUL >>> Inside startRecording >>>>> ");
        if (mSampleFile == null) {
            Log.e (TAG, "ATUL >>> Inside startRecording >>>>> 00000 ");
            File sampleDir = Environment.getExternalStorageDirectory();
            Log.e (TAG, "ATUL >>> Inside startRecording >>>>> 0000 ");
            if (!sampleDir.canWrite()) // Workaround for broken sdcard support on the device.
                sampleDir = new File("/sdcard/sdcard");
            
            try {
                Log.e (TAG, "ATUL >>> Inside startRecording >>>>> inside sampleDir ");

                mSampleFile = File.createTempFile(SAMPLE_PREFIX, extension, sampleDir);
            } catch (IOException e) {
                Log.e (TAG, "ATUL >>> Inside startRecording >>>>> ERROR 000 ");
                setError(SDCARD_ACCESS_ERROR);
                return;
            }
        }
        
        mRecorder = new MediaRecorder();
        mRecorder.setAudioSource(audiosourcetype);
        mRecorder.setOutputFormat(outputfileformat);

        try {
            mRecorder.setAudioEncoder(codectype);
            Log.e (TAG, "ATUL >>> Inside startRecording >>>>> try block ");
        } catch(RuntimeException exception) {
            setError(UNSUPPORTED_FORMAT);
            mRecorder.reset();
            mRecorder.release();
            if (mSampleFile != null)
               mSampleFile.delete();
            mSampleFile = null;
            mSampleLength = 0;
            mRecorder = null;
            return;
        }

        mRecorder.setOutputFile(mSampleFile.getAbsolutePath());
        Log.e (TAG, "ATUL >>> Inside startRecording >>>>> setOutputFile ");
        // Handle IOException
        try {
            mRecorder.prepare();
        } catch(IOException exception) {
            setError(INTERNAL_ERROR);
            mRecorder.reset();
            mRecorder.release();
            if (mSampleFile != null)
               mSampleFile.delete();
            mSampleFile = null;
            mSampleLength = 0;
            mRecorder = null;
            return;
        }
        // Handle RuntimeException if the recording couldn't start
        Log.e(TAG,"audiosourcetype " +audiosourcetype);
        try {
            mRecorder.start();
            Log.e (TAG, "ATUL >>> Inside startRecording >>>>> start recordding ");
        } catch (RuntimeException exception) {
            AudioManager audioMngr = (AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
            boolean isInCall = audioMngr.getMode() == AudioManager.MODE_IN_CALL;
            if (isInCall) {
                setError(IN_CALL_RECORD_ERROR);
                Log.e (TAG, "ATUL >>> Inside startRecording >>>>> IN_CALL_RECORD_ERROR ");
            } else {
                setError(INTERNAL_ERROR);
                Log.e (TAG, "ATUL >>> Inside startRecording >>>>> INTERNAL_ERROR ");
            }
            mRecorder.reset();
            mRecorder.release();
            mRecorder = null;
            return;
        }
        mSampleStart = System.currentTimeMillis();
        setState(RECORDING_STATE);
        Log.e (TAG, "ATUL >>> Inside startRecording >>>>> end recording ");
    }
    
    public void stopRecording() {
        if (mRecorder == null)
            return;

        mRecorder.stop();
        mRecorder.reset();
        mRecorder.release();
        mRecorder = null;

        mSampleLength = (int)( (System.currentTimeMillis() - mSampleStart)/1000 );
        setState(IDLE_STATE);
    }
    
    public void startPlayback() {
        stop();
        
        mPlayer = new MediaPlayer();
        try {
            mPlayer.setDataSource(mSampleFile.getAbsolutePath());
            mPlayer.setOnCompletionListener(this);
            mPlayer.setOnErrorListener(this);
            mPlayer.prepare();
            mPlayer.start();
        } catch (IllegalArgumentException e) {
            setError(INTERNAL_ERROR);
            mPlayer = null;
            return;
        } catch (IOException e) {
            setError(SDCARD_ACCESS_ERROR);
            mPlayer = null;
            return;
        }
        
        mSampleStart = System.currentTimeMillis();
        setState(PLAYING_STATE);
    }
    
    public void stopPlayback() {
        if (mPlayer == null) // we were not in playback
            return;

        mPlayer.stop();
        mPlayer.release();
        mPlayer = null;
        setState(IDLE_STATE);
    }
    
    public void stop() {
        stopRecording();
        stopPlayback();
    }

    public boolean onError(MediaPlayer mp, int what, int extra) {
        stop();
        setError(SDCARD_ACCESS_ERROR);
        return true;
    }

    public void onCompletion(MediaPlayer mp) {
        stop();
    }
    
    private void setState(int state) {
        if (state == mState)
            return;
        
        mState = state;
        signalStateChanged(mState);
    }
    
    private void signalStateChanged(int state) {
        if (mOnStateChangedListener != null)
            mOnStateChangedListener.onStateChanged(state);
    }
    
    private void setError(int error) {
        if (mOnStateChangedListener != null)
            mOnStateChangedListener.onError(error);
    }
}
