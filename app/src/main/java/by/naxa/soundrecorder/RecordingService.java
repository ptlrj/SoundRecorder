package by.naxa.soundrecorder;

import android.app.Service;
import android.content.Intent;
import android.media.MediaRecorder;
import android.os.Binder;
import android.os.Environment;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;

import com.coremedia.iso.boxes.Container;
import com.crashlytics.android.Crashlytics;
import com.googlecode.mp4parser.FileDataSourceImpl;
import com.googlecode.mp4parser.authoring.Movie;
import com.googlecode.mp4parser.authoring.Track;
import com.googlecode.mp4parser.authoring.builder.DefaultMp4Builder;
import com.googlecode.mp4parser.authoring.container.mp4.MovieCreator;
import com.googlecode.mp4parser.authoring.tracks.AppendTrack;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;

import static by.naxa.soundrecorder.R.string;

/**
 * Created by Daniel on 12/28/2014.
 */
public class RecordingService extends Service {

    private static final String LOG_TAG = "RecordingService";

    private String mFileName = null;
    private String mFilePath = null;

    private MediaRecorder mRecorder = null;

    private DBHelper mDatabase;

    private long mStartingTimeMillis = 0;
    private long mElapsedMillis = 0;

    private boolean isPaused;
    private int tempFileCount = 0;

    private ArrayList<String> filesPaused = new ArrayList<>();
    private ArrayList<Long> pauseDurations = new ArrayList<>();

    // Binder given to clients
    private final IBinder mBinder = new LocalBinder();


    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mDatabase = new DBHelper(getApplicationContext());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startRecording();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (mRecorder != null) {
            stopRecording();
        }

        super.onDestroy();
    }

    public void setFileNameAndPath(boolean isFilePathTemp) {
        if (isFilePathTemp) {
            mFileName = getString(string.default_file_name) + (++tempFileCount) + "_" + ".tmp";
            mFilePath = Paths.combine(Environment.getExternalStorageDirectory().getAbsolutePath(),
                    Paths.SOUND_RECORDER_FOLDER, mFileName);
        } else {
            int count = 0;
            File f;

            do {
                ++count;

                mFileName =
                        getString(string.default_file_name) + "_" + (mDatabase.getCount() + count) + ".mp4";

                mFilePath = Paths.combine(
                        Environment.getExternalStorageDirectory().getAbsolutePath(),
                        Paths.SOUND_RECORDER_FOLDER, mFileName);

                f = new File(mFilePath);
            } while (f.exists() && !f.isDirectory());
        }
    }

    public void startRecording() {
        boolean isTemporary = true;
        setFileNameAndPath(isTemporary);

        mRecorder = new MediaRecorder();
        mRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        mRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        mRecorder.setOutputFile(mFilePath);
        mRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        mRecorder.setAudioChannels(1);
        if (MySharedPreferences.getPrefHighQuality(this)) {
            mRecorder.setAudioSamplingRate(44100);
            mRecorder.setAudioEncodingBitRate(192000);
        }

        try {
            mRecorder.prepare();
            mRecorder.start();
            isPaused = false;
            mStartingTimeMillis = System.currentTimeMillis();
        } catch (IOException e) {
            // TODO propagate this exception to MainActivity
            Crashlytics.logException(e);
            Log.e(LOG_TAG, "prepare() failed");
        }
    }

    public void pauseRecording() {
        if (isPaused)
            return;

        mRecorder.stop();
        isPaused = true;
        mElapsedMillis = (System.currentTimeMillis() - mStartingTimeMillis);
        pauseDurations.add(mElapsedMillis);
        Toast.makeText(this, getString(string.toast_recording_paused), Toast.LENGTH_LONG).show();

        filesPaused.add(mFilePath);
    }

    public void stopRecording() {
        if (!isPaused)
            filesPaused.add(mFilePath);

        boolean isTemporary = false;
        setFileNameAndPath(isTemporary);

        if (!isPaused) {
            mRecorder.stop();
            mElapsedMillis = (System.currentTimeMillis() - mStartingTimeMillis);
        }
        mRecorder.release();
        Toast.makeText(this, getString(string.toast_recording_finish) + " " + mFilePath, Toast.LENGTH_LONG).show();

        isPaused = false;

        mRecorder = null;
        if (filesPaused != null && !filesPaused.isEmpty()) {
            if (makeSingleFile(filesPaused)) {
                for (long duration : pauseDurations)
                    mElapsedMillis += duration;
            }
        }

        try {
            mDatabase.addRecording(mFileName, mFilePath, mElapsedMillis);
        } catch (Exception e) {
            Crashlytics.logException(e);
            Log.e(LOG_TAG, "exception", e);
        }
    }

    /**
     * collect temp generated files because of pause to one target file
     *
     * @param filesPaused contains all temp files due to pause
     */
    private boolean makeSingleFile(ArrayList<String> filesPaused) {
        ArrayList<Track> tracks = new ArrayList<>();
        Movie finalMovie = new Movie();
        for (String filePath : filesPaused) {
            try {
                Movie movie = MovieCreator.build(new FileDataSourceImpl(filePath));
                List<Track> movieTracks = movie.getTracks();
                tracks.addAll(movieTracks);
            } catch (IOException e) {
                Crashlytics.logException(e);
                e.printStackTrace();
                return false;
            }
        }

        if (tracks.size() > 0) {
            try {
                finalMovie.addTrack(new AppendTrack(tracks.toArray(new Track[tracks.size()])));
            } catch (IOException e) {
                Crashlytics.logException(e);
                e.printStackTrace();
            }
        }

        Container mp4file = new DefaultMp4Builder().build(finalMovie);
        FileChannel fc = null;
        try {
            fc = new FileOutputStream(new File(mFilePath)).getChannel();
        } catch (FileNotFoundException e) {
            Crashlytics.logException(e);
            e.printStackTrace();
            return false;
        }
        try {
            mp4file.writeContainer(fc);
            fc.close();
            return true;
        } catch (IOException e) {
            Crashlytics.logException(e);
            e.printStackTrace();
            return false;
        }

    }

    public void resumeRecording() {
        isPaused = false;
        startRecording();
    }

    /**
     * Class used for the client Binder.  Because we know this service always
     * runs in the same process as its clients, we don't need to deal with IPC.
     */
    public class LocalBinder extends Binder {
        public RecordingService getService() {
            // Return this instance of LocalService so clients can call public methods
            return RecordingService.this;
        }
    }
}
