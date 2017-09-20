package com.example.terahnharrison.pionear;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Environment;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.util.SparseArray;
import android.view.SurfaceView;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.vision.Frame;
import com.google.android.gms.vision.text.TextBlock;
import com.google.android.gms.vision.text.TextRecognizer;
import com.spotify.sdk.android.authentication.AuthenticationClient;
import com.spotify.sdk.android.authentication.AuthenticationRequest;
import com.spotify.sdk.android.authentication.AuthenticationResponse;
import com.spotify.sdk.android.player.Config;
import com.spotify.sdk.android.player.ConnectionStateCallback;
import com.spotify.sdk.android.player.Error;
import com.spotify.sdk.android.player.Player;
import com.spotify.sdk.android.player.PlayerEvent;
import com.spotify.sdk.android.player.Spotify;
import com.spotify.sdk.android.player.SpotifyPlayer;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;

import kaaes.spotify.webapi.android.SpotifyApi;
import kaaes.spotify.webapi.android.SpotifyService;
import kaaes.spotify.webapi.android.models.Album;
import kaaes.spotify.webapi.android.models.Track;
import kaaes.spotify.webapi.android.models.TracksPager;
import retrofit.*;
import retrofit.client.Response;
import retrofit.converter.ConversionException;
import retrofit.converter.Converter;
import retrofit.mime.TypedByteArray;
import retrofit.mime.TypedInput;
import retrofit.mime.TypedOutput;

import static com.spotify.sdk.android.authentication.AuthenticationResponse.Type.ERROR;
import static com.spotify.sdk.android.authentication.AuthenticationResponse.Type.TOKEN;
import static com.spotify.sdk.android.authentication.LoginActivity.REQUEST_CODE;
import static com.spotify.sdk.android.authentication.LoginActivity.getResponseFromIntent;
import static junit.framework.Assert.assertEquals;

public class MainActivity extends AppCompatActivity implements ConnectionStateCallback, SpotifyPlayer.NotificationCallback {

    public static final int IMAGE_GALLERY_REQUEST = 1;
    public static final int CROP_PIC_REQUEST_CODE = 2;
    private static final String TAG = "MainActivity";

    // Request code will be used to verify if result comes from the login activity. Can be set to any integer.
    private static final int REQUEST_CODE = 1337;
    private static final String REDIRECT_URI = "pionear-login://callback";
    private static final String CLIENT_ID = "d51afc404b1c452ebc6cbb86d5fbdd5a";
    private static String spotifyToken;

    private static SpotifyApi api;
    private static SpotifyService spotify;

    private ImageView imgPicture;
    private TextView songText;

    private Player mPlayer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        //get a reference to the image view
        imgPicture = (ImageView) findViewById(R.id.imgPicture);
        songText = (TextView) findViewById(R.id.song_text);

        //Handle external image share
        Intent intent = getIntent();
        String action = intent.getAction();
        String type = intent.getType();

        spotifySetup();

        if (Intent.ACTION_SEND.equals(action) && type != null) {
            spotifySetup();
            Log.d(TAG, "Image send detected");
            if (type.startsWith("image/")) {
                Uri imageUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);

                imageHandler(imageUri);
            }
        }
    }

    //This method will be invoked when gallery button clicked
    public void onImageGalleryClick(View v) {
        spotifySetup();
        //invoke image gallery using an implicit intent
        Intent photoPickerIntent = new Intent(Intent.ACTION_PICK);

        //where do we want to find the data?
        File pictureDirectory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
        String pictureDirectoryPath =  pictureDirectory.getPath();

        //finally get a URI representation
        Uri data = Uri.parse(pictureDirectoryPath);

        Log.d(TAG, "Detected a image gallery click");

        //set the data and type
        photoPickerIntent.setDataAndType(data, "image/*");

        //we will invoke this activity, and get something back from it
        startActivityForResult(photoPickerIntent, IMAGE_GALLERY_REQUEST);

    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if(resultCode == RESULT_OK) {
            // if we are here, everything processed successfully

            //handles image from gallery
            if(requestCode == IMAGE_GALLERY_REQUEST) {
                Uri imageUri = data.getData();
                imageHandler(imageUri);
            }

            //handles image crop
            if(requestCode == CROP_PIC_REQUEST_CODE) {
                Bundle extras = data.getExtras();
                if(extras != null ) {
                    Bitmap photo = extras.getParcelable("data");
                    imgPicture.setImageBitmap(photo);

                }

            }

            // Check if result comes from the correct activity
            if (requestCode == REQUEST_CODE) {
                AuthenticationResponse response = AuthenticationClient.getResponse(resultCode, data);
                Log.d(TAG, "Token used in onActivityResult: " + response.getAccessToken());
                spotifyToken = response.getAccessToken();
                if (spotifyToken.equals(null)) {
                    Log.d(TAG, "Didn't fetch a token");
                }
                Log.d(TAG, "spotifyToken assigned: " + spotifyToken);

                api = new SpotifyApi();
                api.setAccessToken(spotifyToken);
                spotify = api.getService();

                if (response.getType() == AuthenticationResponse.Type.TOKEN) {
                    Config playerConfig = new Config(this, response.getAccessToken(), CLIENT_ID);
                    Spotify.getPlayer(playerConfig, this, new SpotifyPlayer.InitializationObserver() {
                        @Override
                        public void onInitialized(SpotifyPlayer spotifyPlayer) {
                            mPlayer = spotifyPlayer;
                            mPlayer.addConnectionStateCallback(MainActivity.this);
                            mPlayer.addNotificationCallback(MainActivity.this);
                        }

                        @Override
                        public void onError(Throwable throwable) {
                            Log.e("MainActivity", "Could not initialize player: " + throwable.getMessage());
                        }
                    });
                }
            }
        }
    }


    private void ocrStart(Bitmap image) {
        Context context = getApplicationContext();

        //Build frame
        Frame frame = new Frame.Builder()
                .setBitmap(image)
                .build();

        //Create the text recognizer
        TextRecognizer textRecognizer = new TextRecognizer.Builder(context).build();

        // TODO: Check if the TextRecognizer is operational.
        if (!textRecognizer.isOperational()) {
            Log.w(TAG, "Detector dependencies are not yet available.");

            // Check for low storage.  If there is low storage, the native library will not be
            // downloaded, so detection will not become operational.
            IntentFilter lowstorageFilter = new IntentFilter(Intent.ACTION_DEVICE_STORAGE_LOW);
            boolean hasLowStorage = registerReceiver(null, lowstorageFilter) != null;

            if (hasLowStorage) {
                Toast.makeText(this, "Low Storage, Cannot Complete", Toast.LENGTH_LONG).show();
                Log.w(TAG, "Low Storage, Cannot Complete");
            }
        }

        SparseArray<TextBlock> text = textRecognizer.detect(frame);

        Log.w(TAG, "Size of Sparse Array: " + text.size());
        for(int i = 0; i < text.size(); i++) {
            try {
                Log.w(TAG, "Array value " + i + ": " + text.get(i).getValue());
            }

            catch(NullPointerException n) {
                continue;
            }
        }

        String song = text.get(0).getValue();
        String lines[] = song.split("\\r?\\n");


        songText.setText("Song: " + lines[0] + "\nArtist: " + lines[1]);

        spotify.searchTracks(song, new Callback<TracksPager>() {
            @Override
            public void success(TracksPager tracksPager, Response response) {
                if(tracksPager.tracks.items.size() == 0) {
                    Toast.makeText(getApplicationContext(), "Couldn't find song", Toast.LENGTH_LONG).show();
                    return;
                }

                Log.d(TAG, "TracksPager tracks toString() " + tracksPager.tracks.items.get(0).uri);
                String songURI = tracksPager.tracks.items.get(0).uri;
                songURI = songURI.substring(14);
                Log.d(TAG, "songUri (just URI): " + songURI);

                String url = "https://open.spotify.com/track/" + songURI;
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setData(Uri.parse(url));
                startActivity(i);
            }

            @Override
            public void failure(RetrofitError error) {
                Log.d("Track failure", error.toString());
            }
        });

    }


    public static Bitmap cropImage(Bitmap bitmap){
        int width  = bitmap.getWidth();
        int height = bitmap.getHeight();
        int newWidth = (height > width) ? width : height;
        int newHeight = 200;
        int cropW = (width - height) / 2 - 5000;
        cropW = (cropW < 0)? 0: cropW;
        int cropH = ((height - width) / 2) + width/2 + width/5;
        cropH = (cropH < 0)? 0: cropH;
        Bitmap cropImg = Bitmap.createBitmap(bitmap, cropW, cropH, newWidth, newHeight);

        return cropImg;
    }


    //Handles images both from external share and internal gallery pick
    private void imageHandler(Uri  imageUri) {
        Log.d(TAG, "imageHandler method called");
        //crop image

        //declare a stream to read the image data from the SD card
        InputStream inputStream;

        // we are getting an input stream based on the URI of the image
        try {
            inputStream = getContentResolver().openInputStream(imageUri);

            Bitmap image = BitmapFactory.decodeStream(inputStream);
            image = cropImage(image);

            //show the image to the user
            imgPicture.setImageBitmap(image);

            ocrStart(image);


        } catch (FileNotFoundException e) {
            e.printStackTrace();
            Toast.makeText(this, "Unable to open image", Toast.LENGTH_LONG).show();
        }
    }

    //Handles spotify authentication at application startup
    private void spotifySetup() {
        //Spotify Android SDK authentication
        AuthenticationRequest.Builder builder =
                new AuthenticationRequest.Builder(CLIENT_ID, AuthenticationResponse.Type.TOKEN, REDIRECT_URI);

        builder.setScopes(new String[]{"streaming"});
        AuthenticationRequest request = builder.build();

        SpotifyApi api = new SpotifyApi();

        AuthenticationClient.openLoginActivity(this, REQUEST_CODE, request);
    }

    @Override
    protected void onDestroy() {
        Spotify.destroyPlayer(this);
        super.onDestroy();
    }

    @Override
    public void onLoggedIn() {

    }

    @Override
    public void onLoggedOut() {

    }

    @Override
    public void onLoginFailed(Error error) {

    }

    @Override
    public void onTemporaryError() {

    }

    @Override
    public void onConnectionMessage(String s) {

    }

    @Override
    public void onPlaybackEvent(PlayerEvent playerEvent) {

    }

    @Override
    public void onPlaybackError(Error error) {

    }
}
